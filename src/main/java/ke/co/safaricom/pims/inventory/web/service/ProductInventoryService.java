package ke.co.safaricom.pims.inventory.web.service;

import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.CreateStockAdjustmentRequest;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.api.dto.StockAdjustmentResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDocUtils;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ErrorCode;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.mapper.InventoryMapper;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.util.ItemExtrasCodec;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
import ke.co.safaricom.pims.inventory.web.util.ManufacturersCatalog;
import ke.co.safaricom.pims.inventory.web.util.TerminologyStub;
import ke.co.safaricom.pims.inventory.service.CategoryService;
import ke.co.safaricom.pims.inventory.service.InventoryService;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ProductInventoryService {

    private static final Logger logger = LoggerFactory.getLogger(ProductInventoryService.class);

    /** TTL for the per-tenant order-frequency cache (5 minutes). */
    private static final long ORDER_FREQ_TTL_MS = 5 * 60 * 1_000L;

    private static final ParameterizedTypeReference<ErpNextListResponse<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    /** Per-instance cache: tenantId → aggregated Sales Invoice Item qty per item_code. */
    private final Map<String, CacheEntry> orderFreqCache = new ConcurrentHashMap<>();

    private record CacheEntry(Map<String, Double> data, long expiresAt) {
        boolean isAlive() { return System.currentTimeMillis() < expiresAt; }
    }

    private final ErpNextTenantRouter router;
    private final InventoryMapper inventoryMapper;
    private final InventoryService inventoryService;
    private final CategoryService categoryService;
    private final ProductDraftMemoryStore drafts;

    public ProductInventoryService(
            ErpNextTenantRouter router,
            InventoryMapper inventoryMapper,
            InventoryService inventoryService,
            CategoryService categoryService,
            ProductDraftMemoryStore drafts) {
        this.router = router;
        this.inventoryMapper = inventoryMapper;
        this.inventoryService = inventoryService;
        this.categoryService = categoryService;
        this.drafts = drafts;
    }

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<Map<String, Object>>> RAW_SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};

    // frappe.client.submit is an /api/method/* RPC — Frappe wraps its return value in
    // {"message": ...}, not the {"data": ...} envelope used by /api/resource/* endpoints.
    private static final ParameterizedTypeReference<ErpNextMessageResponse<ErpNextDoc>> SUBMIT_TYPE =
            new ParameterizedTypeReference<>() {};

    /**
     * Lists products with optional filtering, sorted by most-ordered (default) or alphabetically.
     *
     * <p>{@code sort="most_ordered"} (default) — items with the highest total submitted Sales
     * Invoice qty appear first; items with no sales history come last, sorted alphabetically
     * among themselves. Order frequency is fetched from ERPNext's {@code Sales Invoice Item}
     * child doctype and cached per-tenant for {@value #ORDER_FREQ_TTL_MS} ms. If the ERPNext
     * call fails, the sort falls back to alphabetical automatically.
     *
     * <p>{@code sort="alphabetical"} — skips the ERPNext call entirely and sorts by product
     * name only.
     */
    public Mono<InventoryApiSchemas.ProductListResponse> listProducts(
            String tenantId,
            int page,
            int limit,
            String search,
            String category,
            Enums.ProductStatus statusFilter,
            UUID manufacturerIdFilter,
            String sort) {
        return listItemsEnriched(tenantId)
                .zipWith(inventoryService.listBatches(tenantId))
                .flatMap(tuple -> {
                    List<InventoryItemResponse> items = tuple.getT1();
                    List<BatchResponse> batches = tuple.getT2();
                    List<String> itemCodes = items.stream().map(InventoryItemResponse::id).toList();
                    return Mono.zip(
                            inventoryService.getStockLevels(tenantId, itemCodes, inventoryService.defaultWarehouse()),
                            fetchOrderFrequency(tenantId, sort),
                            inventoryService.getSellingPrices(tenantId, itemCodes)
                                    .onErrorReturn(Map.of()))
                            .map(stockTuple -> {
                                Map<String, Double> stockLevels = stockTuple.getT1();
                                Map<String, Double> orderFreq  = stockTuple.getT2();
                                Map<String, Double> sellingPrices = stockTuple.getT3();

                                Map<String, List<BatchResponse>> byItem =
                                        batches.stream().collect(Collectors.groupingBy(BatchResponse::productId));

                                // Pre-compute UUID→frequency for the sort comparator
                                Map<UUID, Double> orderFreqByUuid = new HashMap<>();
                                for (InventoryItemResponse it : items) {
                                    orderFreqByUuid.put(
                                            StableEntityIds.itemId(tenantId, it.id()),
                                            orderFreq.getOrDefault(it.id(), 0.0));
                                }

                                List<InventoryApiSchemas.ProductSummary> rows = new ArrayList<>();
                                for (InventoryItemResponse it : items) {
                                    List<BatchResponse> itemBatches = byItem.getOrDefault(it.id(), List.of());
                                    Map<String, Object> ex = mergedExtras(it);
                                    double available = stockLevels.getOrDefault(it.id(), 0.0);
                                    InventoryApiSchemas.ProductSummary p = toSummary(tenantId, it, itemBatches, available, sellingPrices);
                                    if (!matchesSearch(p, search, ex)) continue;
                                    if (category != null && !category.isBlank()
                                            && (p.category() == null || !p.category().equalsIgnoreCase(category))) continue;
                                    if (manufacturerIdFilter != null) {
                                        Object mid = ex.get("manufacturer_id");
                                        if (mid == null) continue;
                                        try {
                                            if (!UUID.fromString(mid.toString()).equals(manufacturerIdFilter)) continue;
                                        } catch (Exception ignored) {
                                            continue;
                                        }
                                    }
                                    if (statusFilter != null && p.status().stream().noneMatch(s -> s == statusFilter)) continue;
                                    rows.add(p);
                                }

                                sortProductRows(rows, orderFreqByUuid);
                                long total = rows.size();
                                int safeLimit = clampLimit(limit);
                                int pg = normalizePage(page);
                                int from = Math.max(0, (pg - 1) * safeLimit);
                                int to = Math.min(rows.size(), from + safeLimit);
                                List<InventoryApiSchemas.ProductSummary> slice = from >= rows.size() ? List.of() : rows.subList(from, to);
                                InventoryApiSchemas.Pagination pageObj = new InventoryApiSchemas.Pagination(pg, safeLimit, total, calcTotalPages(total, safeLimit));
                                return new InventoryApiSchemas.ProductListResponse(slice, pageObj,
                                        new InventoryApiSchemas.ProductListSummary(total, (long) batches.size()));
                            });
                });
    }

    /**
     * Fetches per-item total ordered qty from submitted {@code Sales Invoice Item} records,
     * aggregated by {@code item_code}. Results are cached per tenant with a 5-minute TTL.
     *
     * <p>Returns an empty map (triggering alphabetical fallback) when:
     * <ul>
     *   <li>{@code sort} equals {@code "alphabetical"} (ERPNext call skipped entirely), or</li>
     *   <li>the ERPNext call fails for any reason (warning is logged).</li>
     * </ul>
     */
    private Mono<Map<String, Double>> fetchOrderFrequency(String tenantId, String sort) {
        if ("alphabetical".equalsIgnoreCase(sort)) {
            return Mono.just(Map.of());
        }
        CacheEntry cached = orderFreqCache.get(tenantId);
        if (cached != null && cached.isAlive()) {
            return Mono.just(cached.data());
        }
        Map<String, String> params = new HashMap<>();
        params.put("fields", "[\"item_code\",\"qty\"]");
        params.put("filters", "[[\"docstatus\",\"=\",1]]");
        params.put("limit_page_length", "500");
        return router.getList(tenantId, "Sales Invoice Item", params, LIST_MAP_TYPE)
                .map(resp -> {
                    Map<String, Double> freq = new HashMap<>();
                    for (Map<String, Object> row : resp.data()) {
                        String code = Objects.toString(row.get("item_code"), null);
                        if (code == null || code.isBlank()) continue;
                        freq.merge(code, toDoubleOrZero(row.get("qty")), (a, b) -> a + b);
                    }
                    Map<String, Double> snapshot = Map.copyOf(freq);
                    orderFreqCache.put(tenantId,
                            new CacheEntry(snapshot, System.currentTimeMillis() + ORDER_FREQ_TTL_MS));
                    return snapshot;
                })
                .onErrorResume(ex -> {
                    logger.warn("Could not fetch Sales Invoice Items for order-frequency sort; " +
                            "falling back to alphabetical. Reason: {}", ex.getMessage());
                    return Mono.just(Map.of());
                });
    }

    /**
     * Sorts rows by order frequency (descending), then alphabetically by product name for ties
     * and zero-frequency items. Falls back to purely alphabetical when {@code orderFreqByUuid}
     * is empty (i.e., no Sales Invoice data was available).
     */
    private static void sortProductRows(
            List<InventoryApiSchemas.ProductSummary> rows, Map<UUID, Double> orderFreqByUuid) {
        if (orderFreqByUuid.isEmpty() || orderFreqByUuid.values().stream().allMatch(v -> v == 0.0)) {
            rows.sort(Comparator.comparing(
                    InventoryApiSchemas.ProductSummary::productName, String.CASE_INSENSITIVE_ORDER));
            return;
        }
        rows.sort(Comparator
                .<InventoryApiSchemas.ProductSummary, Double>comparing(
                        p -> orderFreqByUuid.getOrDefault(p.id(), 0.0),
                        Comparator.reverseOrder())
                .thenComparing(InventoryApiSchemas.ProductSummary::productName, String.CASE_INSENSITIVE_ORDER));
    }

    private static double toDoubleOrZero(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0;
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static Map<String, Object> mergedExtras(InventoryItemResponse it) {
        Map<String, Object> ex = new HashMap<>(ItemExtrasCodec.parse(it.genericName()));
        for (Map.Entry<String, Object> e : it.pimsCustomColumns().entrySet()) {
            Object v = e.getValue();
            if (v != null && !(v instanceof String s && s.isBlank())) {
                ex.put(e.getKey(), v);
            }
        }
        return ex;
    }

    private static void applyCreateCustomFields(Map<String, Object> body, InventoryApiSchemas.CreateProductRequest req) {
        putCustom(body, "custom_pims_ppb_code", req.ppbCode());
        putCustom(body, "custom_pims_ndc_code", req.ndcCode());
        if (req.regulatoryStatus() != null) {
            body.put("custom_pims_regulatory_status", req.regulatoryStatus().name());
        }
        putCustom(body, "custom_pims_strength", req.strength());
        putCustom(body, "custom_pims_dosage_form", req.dosageForm());
        putCustom(body, "custom_pims_additional_notes", req.additionalNotes());
        putCustom(body, "custom_pims_terminology_source", req.terminologySource());
        putCustom(body, "custom_pims_terminology_id", req.terminologyId());
        if (req.manufacturerId() != null) {
            body.put("custom_pims_manufacturer_uuid", req.manufacturerId().toString());
        }
        if (req.unitOfMeasure() != null) {
            body.put("custom_pims_unit_of_measure", req.unitOfMeasure().jsonName());
        }
        if (req.reorderLevel() != null) {
            body.put("custom_pims_reorder_level", req.reorderLevel());
        }
        if (req.maximumStock() != null) {
            body.put("custom_pims_maximum_stock", req.maximumStock());
        }
        putCustom(body, "custom_pims_generic_name", req.genericName());
        InventoryApiSchemas.SpecialRequirements sr = req.specialRequirements();
        if (sr != null) {
            if (sr.controlledSubstance() != null) {
                body.put("custom_pims_controlled_substance", Boolean.TRUE.equals(sr.controlledSubstance()) ? 1 : 0);
            }
            putCustom(body, "custom_pims_controlled_schedule", sr.controlledSubstanceSchedule());
            if (sr.coldChainRequired() != null) {
                body.put("custom_pims_cold_chain_required", Boolean.TRUE.equals(sr.coldChainRequired()) ? 1 : 0);
            }
            if (sr.photosensitive() != null) {
                body.put("custom_pims_photosensitive", Boolean.TRUE.equals(sr.photosensitive()) ? 1 : 0);
            }
            if (sr.photosensitiveShelfLifeMonths() != null) {
                body.put("custom_pims_photosensitive_shelf_life_months", sr.photosensitiveShelfLifeMonths());
            }
        }
    }

    private static void putCustom(Map<String, Object> body, String key, String val) {
        if (val != null && !val.isBlank()) {
            body.put(key, val);
        }
    }

    private static void populateCustomFieldsFromDoc(Map<String, Object> body, ErpNextDoc doc) {
        putCustom(body, "custom_pims_ppb_code", doc.customPimsPpbCode());
        putCustom(body, "custom_pims_ndc_code", doc.customPimsNdcCode());
        putCustom(body, "custom_pims_regulatory_status", doc.customPimsRegulatoryStatus());
        putCustom(body, "custom_pims_strength", doc.customPimsStrength());
        putCustom(body, "custom_pims_dosage_form", doc.customPimsDosageForm());
        putCustom(body, "custom_pims_terminology_source", doc.customPimsTerminologySource());
        putCustom(body, "custom_pims_terminology_id", doc.customPimsTerminologyId());
        putCustom(body, "custom_pims_additional_notes", doc.customPimsAdditionalNotes());
        putCustom(body, "custom_pims_manufacturer_uuid", doc.customPimsManufacturerUuid());
        putCustom(body, "custom_pims_supplier", doc.customPimsSupplier());
        putCustom(body, "custom_pims_unit_of_measure", doc.customPimsUnitOfMeasure());
        if (doc.customPimsReorderLevel() != null) {
            body.put("custom_pims_reorder_level", doc.customPimsReorderLevel());
        }
        if (doc.customPimsMaximumStock() != null) {
            body.put("custom_pims_maximum_stock", doc.customPimsMaximumStock());
        }
        if (doc.customPimsControlledSubstance() != null) {
            body.put("custom_pims_controlled_substance", doc.customPimsControlledSubstance());
        }
        putCustom(body, "custom_pims_controlled_schedule", doc.customPimsControlledSchedule());
        if (doc.customPimsColdChainRequired() != null) {
            body.put("custom_pims_cold_chain_required", doc.customPimsColdChainRequired());
        }
        if (doc.customPimsPhotosensitive() != null) {
            body.put("custom_pims_photosensitive", doc.customPimsPhotosensitive());
        }
        if (doc.customPimsPhotosensitiveShelfLifeMonths() != null) {
            body.put("custom_pims_photosensitive_shelf_life_months", doc.customPimsPhotosensitiveShelfLifeMonths());
        }
        putCustom(body, "custom_pims_generic_name", doc.customPimsGenericName());
    }

    private static void overlayUpdateCustomFields(Map<String, Object> body, InventoryApiSchemas.UpdateProductRequest u) {
        if (u.ppbCode() != null) {
            body.put("custom_pims_ppb_code", u.ppbCode());
        }
        if (u.ndcCode() != null) {
            body.put("custom_pims_ndc_code", u.ndcCode());
        }
        if (u.regulatoryStatus() != null) {
            body.put("custom_pims_regulatory_status", u.regulatoryStatus().name());
        }
        if (u.strength() != null) {
            body.put("custom_pims_strength", u.strength());
        }
        if (u.dosageForm() != null) {
            body.put("custom_pims_dosage_form", u.dosageForm());
        }
        if (u.additionalNotes() != null) {
            body.put("custom_pims_additional_notes", u.additionalNotes());
        }
        if (u.manufacturerId() != null) {
            body.put("custom_pims_manufacturer_uuid", u.manufacturerId().toString());
        }
        if (u.unitOfMeasure() != null) {
            body.put("custom_pims_unit_of_measure", u.unitOfMeasure().jsonName());
        }
        if (u.reorderLevel() != null) {
            body.put("custom_pims_reorder_level", u.reorderLevel());
        }
        if (u.maximumStock() != null) {
            body.put("custom_pims_maximum_stock", u.maximumStock());
        }
        if (u.genericName() != null) {
            body.put("custom_pims_generic_name", u.genericName());
        }
        InventoryApiSchemas.SpecialRequirements sr = u.specialRequirements();
        if (sr != null) {
            if (sr.controlledSubstance() != null) {
                body.put("custom_pims_controlled_substance", Boolean.TRUE.equals(sr.controlledSubstance()) ? 1 : 0);
            }
            if (sr.controlledSubstanceSchedule() != null) {
                body.put("custom_pims_controlled_schedule", sr.controlledSubstanceSchedule());
            }
            if (sr.coldChainRequired() != null) {
                body.put("custom_pims_cold_chain_required", Boolean.TRUE.equals(sr.coldChainRequired()) ? 1 : 0);
            }
            if (sr.photosensitive() != null) {
                body.put("custom_pims_photosensitive", Boolean.TRUE.equals(sr.photosensitive()) ? 1 : 0);
            }
            if (sr.photosensitiveShelfLifeMonths() != null) {
                body.put("custom_pims_photosensitive_shelf_life_months", sr.photosensitiveShelfLifeMonths());
            }
        }
    }

    public Mono<InventoryApiSchemas.ProductDraft> saveDraft(String tenantId, InventoryApiSchemas.ProductDraftRequest req) {
        return Mono.fromCallable(() -> drafts.create(tenantId, req));
    }

    public Mono<InventoryApiSchemas.ProductDraft> getDraft(String tenantId, UUID draftId) {
        return Mono.justOrEmpty(drafts.get(tenantId, draftId)).switchIfEmpty(
                Mono.error(new ResourceNotFoundException(
                        ErrorCode.DRAFT_NOT_FOUND, "Draft not found: " + draftId)));
    }

    public Mono<InventoryApiSchemas.ProductDraft> patchDraft(String tenantId, UUID draftId, InventoryApiSchemas.ProductDraftRequest req) {
        return Mono.fromCallable(() -> drafts.update(tenantId, draftId, req));
    }

    public Mono<InventoryApiSchemas.ProductDetail> getProduct(String tenantId, UUID productId) {
        return listItemsEnriched(tenantId)
                .zipWith(inventoryService.listBatches(tenantId))
                .flatMap(tuple -> {
                    InventoryItemResponse item =
                            tuple.getT1().stream().filter(it -> StableEntityIds.itemId(tenantId, it.id()).equals(productId)).findFirst()
                                    .orElseThrow(() -> new ResourceNotFoundException(
                                            ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));
                    Map<String, List<BatchResponse>> grouped =
                            tuple.getT2().stream().collect(Collectors.groupingBy(BatchResponse::productId));
                    List<BatchResponse> batches = new ArrayList<>(
                            grouped.getOrDefault(item.id(), List.of()));
                    batches.sort(Comparator.comparing(BatchResponse::expiryDate, Comparator.nullsLast(String::compareTo)));
                    InventoryApiSchemas.Pagination pg =
                            new InventoryApiSchemas.Pagination(1, Math.max(1, batches.size()), batches.size(), 1);
                    List<InventoryApiSchemas.Batch> apiBatches =
                            batches.stream().map(b -> toApiBatch(tenantId, item.id(), b)).toList();
                    InventoryApiSchemas.BatchListResponse bl = new InventoryApiSchemas.BatchListResponse(apiBatches, pg);
                    return inventoryService.getStockLevels(tenantId, List.of(item.id()), inventoryService.defaultWarehouse())
                            .zipWith(inventoryService.getSellingPrices(tenantId, List.of(item.id()))
                                    .onErrorReturn(Map.of()))
                            .map(stockAndPrice -> buildDetail(tenantId, item, batches, bl,
                                    stockAndPrice.getT1().getOrDefault(item.id(), 0.0),
                                    stockAndPrice.getT2().getOrDefault(item.id(), null)));
                });
    }

    public Mono<InventoryApiSchemas.ProductDetail> createProduct(String tenantId, InventoryApiSchemas.CreateProductRequest req) {
        validateCreate(req);
        return categoryService.validateLeafCategoryExists(tenantId, req.category())
                .then(inventoryService.listItems(tenantId).flatMap(existing -> {
            if (duplicatePpbOrNdc(existing, req)) {
                return Mono.error(new ConflictException("Duplicate PPB code or NDC already exists"));
            }
            Map<String, Object> body = new HashMap<>();
            String itemCode = "PIMS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            body.put("doctype", "Item");
            body.put("item_code", itemCode);
            body.put("item_name", req.productName());
            body.put("item_group", req.category());
            body.put("stock_uom", req.unitOfMeasure() != null ? mapUom(req.unitOfMeasure()) : "Nos");
            body.put("is_stock_item", 1);
            body.put("has_batch_no", 1);
            body.put("description", ItemExtrasCodec.embed("", req));
            applyCreateCustomFields(body, req);

            return router
                    .create(tenantId, "Item", body, SINGLE_TYPE)
                    .flatMap(created -> chainInitialBatches(tenantId, itemCode, req))
                    .then(upsertSellingPrice(tenantId, itemCode, req.sellingPrice()))
                    .then(getProduct(tenantId, StableEntityIds.itemId(tenantId, itemCode)));
        }));
    }

    public Mono<InventoryApiSchemas.ProductDetail> updateProduct(String tenantId, UUID productId, InventoryApiSchemas.UpdateProductRequest u) {
        Mono<Void> categoryCheck = StringUtils.hasText(u.category())
                ? categoryService.validateLeafCategoryExists(tenantId, u.category())
                : Mono.empty();
        return categoryCheck.then(resolveItemName(tenantId, productId)
                .flatMap(itemName -> router
                        .getOne(tenantId, "Item", itemName, SINGLE_TYPE)
                        .flatMap(one -> {
                            ErpNextDoc doc = one.data();
                            Map<String, Object> body = new HashMap<>();
                            body.put("doctype", "Item");
                            body.put("name", doc.name());
                            body.put("item_code", doc.name());
                            body.put("item_name", u.productName() != null ? u.productName() : doc.itemName());
                            body.put("item_group", u.category() != null ? u.category() : doc.itemGroup());
                            body.put("stock_uom", u.unitOfMeasure() != null ? mapUom(u.unitOfMeasure()) : doc.stockUom());
                            populateCustomFieldsFromDoc(body, doc);
                            overlayUpdateCustomFields(body, u);
                            String desc = ItemExtrasCodec.mergeUpdate(
                                    Optional.ofNullable(doc.description()).orElse(""), u);
                            body.put("description", desc);
                            return router.replace(tenantId, "Item", doc.name(), body, SINGLE_TYPE)
                                    .then(upsertSellingPrice(tenantId, doc.name(), u.sellingPrice()));
                        })
                        .then(getProduct(tenantId, productId))));
    }

    public Mono<Void> deleteProduct(String tenantId, UUID productId) {
        return resolveItemName(tenantId, productId).flatMap(itemName ->
                router.getOne(tenantId, "Item", itemName, SINGLE_TYPE).flatMap(one -> {
                    ErpNextDoc doc = one.data();
                    Map<String, Object> body = new HashMap<>();
                    body.put("item_code", doc.name());
                    body.put("item_name", doc.itemName());
                    body.put("item_group", doc.itemGroup());
                    body.put("stock_uom", doc.stockUom());
                    body.put("disabled", 1);
                    return router.replace(tenantId, "Item", itemName, body, SINGLE_TYPE).then();
                }));
    }

    public Mono<InventoryApiSchemas.BatchListResponse> listBatches(
            String tenantId, UUID productId, int page, int limit, Enums.BatchStatus status, String sort) {
        return resolveItemName(tenantId, productId)
                .flatMap(itemCode -> inventoryService
                        .listBatches(tenantId)
                        .map(list -> filterSortBatches(list, itemCode, status, sort))
                        .map(filtered -> {
                            long total = filtered.size();
                            int lim = clampLimit(limit);
                            int pg = normalizePage(page);
                            int from = Math.max(0, (pg - 1) * lim);
                            int to = Math.min(filtered.size(), from + lim);
                            List<InventoryApiSchemas.Batch> slice =
                                    from >= filtered.size() ? List.of() :
                                            filtered.subList(from, to).stream()
                                                    .map(b -> toApiBatch(tenantId, itemCode, b))
                                                    .toList();
                            return new InventoryApiSchemas.BatchListResponse(
                                    slice,
                                    new InventoryApiSchemas.Pagination(pg, lim, total, calcTotalPages(total, lim)));
                        }));
    }

    public Mono<InventoryApiSchemas.Batch> getBatch(String tenantId, UUID productId, UUID batchId) {
        return resolveItemName(tenantId, productId)
                .flatMap(itemCode -> inventoryService
                        .listBatches(tenantId)
                        .flatMap(list -> {
                            Optional<InventoryApiSchemas.Batch> found = list.stream()
                                    .filter(b -> itemCode.equals(b.productId()))
                                    .filter(b -> StableEntityIds.batchId(tenantId, b.id()).equals(batchId))
                                    .findFirst()
                                    .map(b -> toApiBatch(tenantId, itemCode, b));
                            return found.map(Mono::just).orElseGet(() ->
                                    Mono.error(new ResourceNotFoundException(
                                            ErrorCode.BATCH_NOT_FOUND,
                                            "Batch not found: " + batchId + " for product " + productId)));
                        }));
    }

    public Mono<Void> deleteBatch(String tenantId, UUID productId, UUID batchId) {
        return resolveItemName(tenantId, productId).flatMap(itemCode -> inventoryService
                .listBatches(tenantId)
                .flatMap(list -> {
                    Optional<BatchResponse> match = list.stream()
                            .filter(b -> itemCode.equals(b.productId()))
                            .filter(b -> StableEntityIds.batchId(tenantId, b.id()).equals(batchId))
                            .findFirst();
                    if (match.isEmpty())
                        return Mono.error(new ResourceNotFoundException(
                                ErrorCode.BATCH_NOT_FOUND,
                                "Batch not found: " + batchId + " for product " + productId));
                    return router.delete(tenantId, "Batch", match.get().id());
                }));
    }

    public Mono<Object> createBatchFlexible(String tenantId, UUID productId, InventoryApiSchemas.CreateBatchRequest json, Mono<FilePart> fileMono) {
        return fileMono
                .flatMap(part -> ingestCsvMultipart(tenantId, productId, part))
                .switchIfEmpty(Mono.defer(() -> addBatchJsonReturn(tenantId, productId, json)));
    }

    /** Returns either {@link InventoryApiSchemas.Batch} (JSON creation) or {@link InventoryApiSchemas.BatchBulkUploadResult} map for CSV ingestion. */
    private Mono<Object> ingestCsvMultipart(String tenantId, UUID productId, FilePart part) {
        return resolveItemName(tenantId, productId).flatMap(itemCode -> parseCsv(part)
                .flatMap(rows -> ingestCsvLines(tenantId, itemCode, rows)));
    }

    public Mono<Object> ingestCsvLines(String tenantId, String itemCode, List<String[]> parsedRows) {
        if (parsedRows.isEmpty()) {
            InventoryApiSchemas.BatchBulkUploadResult empty = new InventoryApiSchemas.BatchBulkUploadResult(
                    0, 0, 0, List.of(), List.of());
            return Mono.just(empty);
        }
        return Flux.range(1, parsedRows.size())
                .zipWith(Flux.fromIterable(parsedRows))
                .concatMap(tuple -> {
                    int rowNum = tuple.getT1();
                    String[] cells = tuple.getT2();
                    try {
                        InventoryApiSchemas.CreateBatchRequest req = createBatchFromCsvRow(cells);
                        validateBatch(req);
                        return addBatchRaw(tenantId, itemCode, req)
                                .then(lastCreatedBatchForItem(tenantId, itemCode, req.batchNumber()))
                                .map(b -> new CsvRowOutcome(rowNum, null, b));
                    } catch (Exception ex) {
                        return Mono.just(new CsvRowOutcome(
                                rowNum,
                                new InventoryApiSchemas.BatchCsvErrorRow(rowNum, "row", ex.getMessage()),
                                null));
                    }
                })
                .collectList()
                .map(outcomes -> {
                    List<InventoryApiSchemas.Batch> ok = new ArrayList<>();
                    List<InventoryApiSchemas.BatchCsvErrorRow> err = new ArrayList<>();
                    for (CsvRowOutcome o : outcomes) {
                        if (o.error() != null) err.add(o.error());
                        if (o.batch() != null) ok.add(o.batch());
                    }
                    int total = outcomes.size();
                    return (Object) new InventoryApiSchemas.BatchBulkUploadResult(total, ok.size(), err.size(), err, ok);
                });
    }

    private record CsvRowOutcome(int row, InventoryApiSchemas.BatchCsvErrorRow error, InventoryApiSchemas.Batch batch) {}

    public Mono<InventoryApiSchemas.AdjustmentListResponse> listAdjustments(String tenantId, UUID productId, int page, int limit) {
        return resolveItemName(tenantId, productId)
                .flatMap(itemCode -> inventoryService
                        .listAdjustments(tenantId, itemCode)
                        .map(list -> list)
                        .map(filtered -> {
                            long total = filtered.size();
                            int lim = clampLimit(limit);
                            int pg = normalizePage(page);
                            int from = Math.max(0, (pg - 1) * lim);
                            int to = Math.min(filtered.size(), from + lim);
                            List<InventoryApiSchemas.StockAdjustment> slice =
                                    from >= filtered.size() ? List.of() :
                                            filtered.subList(from, to).stream()
                                                    .map(a -> toApiAdjustment(tenantId, productId, itemCode, a))
                                                    .toList();
                            return new InventoryApiSchemas.AdjustmentListResponse(
                                    slice, new InventoryApiSchemas.Pagination(pg, lim, total, calcTotalPages(total, lim)));
                        }));
    }

    public Mono<InventoryApiSchemas.StockAdjustment> adjustStock(
            String tenantId, UUID productId, InventoryApiSchemas.StockAdjustmentRequest req, String userEmail, String userName) {
        return resolveItemName(tenantId, productId)
                .flatMap(itemCode -> getBatch(tenantId, productId, req.batchId()).flatMap(batch -> {
                    if (Math.abs(batch.quantity() - 0) < 1e-6 && req.adjustmentType() == Enums.AdjustmentDirection.decrease) {
                        return Mono.error(new ke.co.safaricom.pims.inventory.exception.ServiceValidationException(
                                "Cannot decrease empty batch"));
                    }
                    String type = req.adjustmentType() == Enums.AdjustmentDirection.increase ? "addition" : "reduction";
                    CreateStockAdjustmentRequest legacy = new CreateStockAdjustmentRequest(
                            itemCode,
                            batch.storageLocation(),
                            req.quantity(),
                            type,
                            req.reason().name(),
                            batch.batchNumber());
                    return inventoryService
                            .createAdjustment(tenantId, legacy)
                            .map(r -> mapNewAdjustment(
                                    r, tenantId, productId, req, userEmail, userName, batch.quantity(), batch.batchNumber()));
                }));
    }

    public Mono<List<InventoryApiSchemas.Manufacturer>> manufacturers(String query, Integer limit) {
        return Mono.fromCallable(() -> ManufacturersCatalog.filtered(query, limit));
    }

    public Mono<InventoryApiSchemas.TerminologySearchResponse> searchTerminology(
            String q, UUID manufacturerId, Enums.TerminologySource source, int limit) {
        return Mono.fromCallable(() -> TerminologyStub.search(q, manufacturerId, source, limit));
    }

    public Mono<InventoryApiSchemas.TerminologyProduct> terminologyProduct(String terminologyId) {
        return Mono.justOrEmpty(TerminologyStub.product(terminologyId))
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(
                        ErrorCode.TERMINOLOGY_NOT_FOUND, "Terminology product not found: " + terminologyId)));
    }

    // -------------------------------------------------------------------------

    public Mono<InventoryApiSchemas.Batch> addBatchJsonReturn(String tenantId, UUID productId, InventoryApiSchemas.CreateBatchRequest req) {
        validateBatch(req);
        return resolveItemName(tenantId, productId).flatMap(itemCode -> addBatchRaw(tenantId, itemCode, req)
                .then(lastCreatedBatchForItem(tenantId, itemCode, req.batchNumber())));
    }

    private Mono<Void> upsertSellingPrice(String tenantId, String itemCode, Double sellingPrice) {
        if (sellingPrice == null) return Mono.empty();
        Map<String, String> params = new HashMap<>();
        params.put("fields", "[\"name\",\"price_list_rate\"]");
        params.put("filters", "[[\"item_code\",\"=\",\"" + itemCode + "\"],[\"price_list\",\"=\",\"Standard Selling\"],[\"selling\",\"=\",1]]");
        return router.getList(tenantId, "Item Price", params, LIST_MAP_TYPE)
                .flatMap(response -> {
                    Map<String, Object> priceBody = new HashMap<>();
                    priceBody.put("doctype", "Item Price");
                    priceBody.put("item_code", itemCode);
                    priceBody.put("price_list", "Standard Selling");
                    priceBody.put("price_list_rate", sellingPrice);
                    priceBody.put("selling", 1);
                    priceBody.put("currency", "KES");
                    List<Map<String, Object>> existing = response.data();
                    if (!existing.isEmpty()) {
                        String name = (String) existing.get(0).get("name");
                        priceBody.put("name", name);
                        return router.replace(tenantId, "Item Price", name, priceBody, SINGLE_TYPE).then();
                    }
                    return router.create(tenantId, "Item Price", priceBody, SINGLE_TYPE).then();
                });
    }

    private Mono<Void> chainInitialBatches(String tenantId, String itemCode, InventoryApiSchemas.CreateProductRequest req) {
        List<InventoryApiSchemas.CreateBatchRequest> batches = req.initialBatches() == null ? List.of() : req.initialBatches();
        if (batches.isEmpty()) return Mono.empty();
        return Flux.fromIterable(batches)
                .concatMap(b -> {
                    try {
                        validateBatch(b);
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                    return addBatchRaw(tenantId, itemCode, b);
                })
                .then();
    }

    private Mono<Void> addBatchRaw(String tenantId, String itemCode, InventoryApiSchemas.CreateBatchRequest b) {
        Map<String, Object> batchBody = new HashMap<>();
        batchBody.put("doctype", "Batch");
        batchBody.put("batch_id", b.batchNumber());
        batchBody.put("item", itemCode);
        batchBody.put("expiry_date", b.expiryDate());
        batchBody.put("supplier", b.supplier());
        if (b.unitCost() != null) batchBody.put("pims_unit_cost", b.unitCost());
        if (b.tradeCost() != null) batchBody.put("pims_trade_cost", b.tradeCost());
        Mono<Void> createBatch = router.create(tenantId, "Batch", batchBody, SINGLE_TYPE).then();
        if (b.quantity() == null || b.quantity() <= 0) {
            return createBatch;
        }
        return createBatch.then(createInitialStockEntry(tenantId, itemCode, b));
    }

    private Mono<Void> createInitialStockEntry(String tenantId, String itemCode, InventoryApiSchemas.CreateBatchRequest b) {
        String warehouse = StringUtils.hasText(b.storageLocation())
                ? b.storageLocation() : inventoryService.defaultWarehouse();
        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("item_code", itemCode);
        lineItem.put("qty", b.quantity());
        lineItem.put("t_warehouse", warehouse);
        lineItem.put("batch_no", b.batchNumber());
        if (b.unitCost() != null) lineItem.put("basic_rate", b.unitCost());
        Map<String, Object> entry = new HashMap<>();
        entry.put("doctype", "Stock Entry");
        entry.put("stock_entry_type", "Material Receipt");
        entry.put("purpose", "Material Receipt");
        entry.put("remarks", "Initial batch stocking");
        entry.put("items", List.of(lineItem));
        // ERPNext only books quantity into Bin.actual_qty once the Stock Entry is *submitted* —
        // a draft has no stock effect, so the create must be chained into an immediate submit.
        return router.create(tenantId, "Stock Entry", entry, SINGLE_TYPE)
                .map(ErpNextSingleResponse::data)
                .map(ErpNextDoc::name)
                .flatMap(name -> submitStockEntry(tenantId, name))
                .then();
    }

    /**
     * Re-fetches the freshly-created draft <em>in full</em> and submits it via
     * {@code frappe.client.submit} — this is what books the quantity into ERPNext's Bin.
     *
     * <p>The submit RPC reconstructs its working document purely from the {@code "doc"} payload
     * it's handed — {@code frappe.get_doc(dict)} populates an in-memory doc straight from the
     * dict's own keys, with no DB load — so echoing back only {@code {doctype, name, modified,
     * docstatus}} leaves required fields like {@code purpose}/{@code items} empty and fails
     * {@code validate()} with e.g. "Purpose must be one of 'Material Issue', 'Material
     * Receipt', ...". Fetching the complete current document immediately beforehand both
     * supplies everything {@code validate()} needs <em>and</em> naturally satisfies
     * {@code check_if_latest()}'s optimistic-lock comparison on {@code modified} — exactly what
     * the desk UI does when you click Submit (it posts back its locally-cached copy of the
     * loaded document).
     */
    private Mono<ErpNextDoc> submitStockEntry(String tenantId, String name) {
        return router.getOne(tenantId, "Stock Entry", name, RAW_SINGLE_TYPE)
                .map(ErpNextSingleResponse::data)
                .flatMap(latest -> {
                    ErpNextDocUtils.allowZeroValuationRateOnZeroCostItems(latest);
                    Map<String, Object> body = new HashMap<>();
                    body.put("doc", latest);
                    return router.callMethod(tenantId, "frappe.client.submit", body, SUBMIT_TYPE);
                })
                .map(ErpNextMessageResponse::message);
    }

    private Mono<InventoryApiSchemas.Batch> lastCreatedBatchForItem(String tenantId, String itemCode, String batchNumberGuess) {
        return inventoryService
                .listBatches(tenantId)
                .flatMap(list -> {
                    Optional<InventoryApiSchemas.Batch> hit = list.stream()
                            .filter(br -> itemCode.equals(br.productId()))
                            .filter(br ->
                                    batchNumberGuess.equals(br.batchNumber()) || batchNumberGuess.equals(br.id()))
                            .findFirst()
                            .map(br -> toApiBatch(tenantId, itemCode, br));
                    return hit.map(Mono::just).orElseGet(() ->
                            Mono.error(new ResourceNotFoundException(
                                    ErrorCode.BATCH_NOT_FOUND,
                                    "Batch not persisted for item "
                                            + itemCode
                                            + " (batch_number="
                                            + batchNumberGuess
                                            + ")")));
                });
    }

    private Mono<String> resolveItemName(String tenantId, UUID productId) {
        return inventoryService
                .listItems(tenantId)
                .map(list -> list.stream()
                        .filter(it -> StableEntityIds.itemId(tenantId, it.id()).equals(productId))
                        .map(InventoryItemResponse::id)
                        .findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException(
                                ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId)));
    }

    private Mono<List<String[]>> parseCsv(FilePart part) {
        return DataBufferUtils.join(part.content())
                .map(db -> {
                    byte[] bytes = new byte[db.readableByteCount()];
                    db.read(bytes);
                    DataBufferUtils.release(db);
                    return csvRows(new String(bytes, StandardCharsets.UTF_8));
                });
    }

    private static List<String[]> csvRows(String raw) {
        List<String[]> out = new ArrayList<>();
        for (String line : raw.lines().skip(1).toList()) {
            if (!StringUtils.hasText(line)) continue;
            out.add(Arrays.stream(line.split(",")).map(String::trim).toArray(String[]::new));
        }
        return out;
    }

    private InventoryApiSchemas.CreateBatchRequest createBatchFromCsvRow(String[] cells) {
        if (cells.length < 5) {
            throw new IllegalArgumentException(
                    "Expected columns: batch_number,expiry_date,quantity,unit_cost,storage_location");
        }
        return new InventoryApiSchemas.CreateBatchRequest(
                cells[0],
                cells[1],
                null,
                Double.parseDouble(cells[2]),
                null,
                Double.parseDouble(cells[3]),
                cells[4],
                null,
                null);
    }

    private List<BatchResponse> filterSortBatches(
            List<BatchResponse> list, String itemCode, Enums.BatchStatus status, String sort) {
        List<BatchResponse> filtered = list.stream()
                .filter(b -> itemCode.equals(b.productId()))
                .filter(b -> status == null || mappedBatchEnum(b.status()) == status)
                .collect(Collectors.toCollection(ArrayList::new));
        Comparator<BatchResponse> cmp =
                switch (sort == null ? "fefo" : sort) {
                    case "lifo" -> Comparator.comparing(
                            BatchResponse::expiryDate, Comparator.nullsFirst(String::compareTo)).reversed();
                    case "created_at" -> Comparator.comparing(
                            BatchResponse::receivedDate, Comparator.nullsLast(String::compareTo))
                            .thenComparing(BatchResponse::id);
                    default -> Comparator.comparing(
                            BatchResponse::expiryDate, Comparator.nullsLast(String::compareTo));
                };
        filtered.sort(cmp);
        return filtered;
    }

    private InventoryApiSchemas.StockAdjustment toApiAdjustment(
            String tenantId,
            UUID productId,
            String itemCode,
            StockAdjustmentResponse r) {
        UUID adjId = UUID.nameUUIDFromBytes((tenantId + "::adj::" + r.id()).getBytes(StandardCharsets.UTF_8));
        UUID batchId = UUID.nameUUIDFromBytes((tenantId + "::adjbatch::" + r.id()).getBytes(StandardCharsets.UTF_8));
        return new InventoryApiSchemas.StockAdjustment(
                adjId,
                productId,
                batchId,
                "",
                r.type().equals("addition") ? Enums.AdjustmentDirection.increase : Enums.AdjustmentDirection.decrease,
                r.quantity(),
                0,
                r.quantity(),
                Enums.AdjustmentReason.other,
                r.reason(),
                null,
                new InventoryApiSchemas.UserRef(UUID.randomUUID(), Optional.ofNullable(r.user()).orElse("system"), ""),
                r.date());
    }

    private InventoryApiSchemas.StockAdjustment mapNewAdjustment(
            StockAdjustmentResponse r,
            String tenantId,
            UUID productId,
            InventoryApiSchemas.StockAdjustmentRequest req,
            String email,
            String name,
            double qtyBefore,
            String batchNumber) {
        double signed = req.adjustmentType() == Enums.AdjustmentDirection.increase ? req.quantity() : -req.quantity();
        double after = qtyBefore + signed;
        return new InventoryApiSchemas.StockAdjustment(
                UUID.nameUUIDFromBytes((tenantId + "::adj::" + r.id()).getBytes(StandardCharsets.UTF_8)),
                productId,
                req.batchId(),
                batchNumber,
                req.adjustmentType(),
                req.quantity(),
                qtyBefore,
                after,
                req.reason(),
                req.notes(),
                req.referenceNumber(),
                new InventoryApiSchemas.UserRef(UUID.randomUUID(), name, email),
                r.date());
    }

    private Mono<List<InventoryItemResponse>> listItemsEnriched(String tenantId) {
        return inventoryService
                .listItems(tenantId)
                .flatMap(items -> inventoryService
                        .listBatches(tenantId)
                        .map(batches -> {
                            Map<String, List<BatchResponse>> byItem =
                                    batches.stream().collect(Collectors.groupingBy(BatchResponse::productId));
                            List<InventoryItemResponse> out = new ArrayList<>();
                            for (InventoryItemResponse it : items) {
                                out.add(inventoryMapper.copyWithBatches(
                                        it, byItem.getOrDefault(it.id(), List.of())));
                            }
                            return out;
                        }));
    }


    private static boolean isUsable(BatchResponse b) {
        return !"expired".equals(b.status()) && !"recalled".equals(b.status());
    }

    private InventoryApiSchemas.ProductSummary toSummary(
            String tenantId, InventoryItemResponse it, List<BatchResponse> batches, double availableQuantity,
            Map<String, Double> sellingPrices) {
        double total = batches.isEmpty()
                ? availableQuantity
                : batches.stream().filter(ProductInventoryService::isUsable).mapToDouble(BatchResponse::quantity).sum();
        Map<String, Object> ex = mergedExtras(it);
        String genericDisplay = ItemExtrasCodec.displayGenericName(it.genericName(), ex);
        Enums.UnitOfMeasure uom = ex.containsKey("unit_of_measure")
                ? ItemExtrasCodec.uom(ex.get("unit_of_measure").toString())
                : Enums.UnitOfMeasure.fromItemUom(it.unit());
        String cat = resolveCategory(it, ex);
        List<Enums.ProductStatus> statuses = computeStatuses(total, it.reorderLevel(), it.isControlled(), batches);
        Double unitPrice = weightedAverageUnitCost(batches);
        Double tradeCostAvg = weightedAverageTradeCost(batches);
        double totalValue = batches.stream().filter(ProductInventoryService::isUsable)
                .mapToDouble(b -> b.quantity() * b.cost()).sum();
        Double sellingPrice = sellingPrices.getOrDefault(it.id(), null);
        return new InventoryApiSchemas.ProductSummary(
                StableEntityIds.itemId(tenantId, it.id()),
                it.name(),
                genericDisplay,
                cat,
                total,
                availableQuantity,
                uom,
                batches.size(),
                statuses,
                null,
                "",
                "",
                unitPrice,
                unitPrice != null ? "KES" : null,
                tradeCostAvg,
                totalValue > 0 ? totalValue : null,
                sellingPrice);
    }

    private static Double weightedAverageUnitCost(List<BatchResponse> batches) {
        List<BatchResponse> usable = batches.stream().filter(ProductInventoryService::isUsable).toList();
        double totalQty = usable.stream().mapToDouble(BatchResponse::quantity).sum();
        if (totalQty <= 0) {
            return usable.stream().mapToDouble(BatchResponse::cost).filter(c -> c > 0).average().orElse(0) > 0
                    ? usable.stream().mapToDouble(BatchResponse::cost).filter(c -> c > 0).average().getAsDouble()
                    : null;
        }
        double weightedSum = usable.stream().mapToDouble(b -> b.quantity() * b.cost()).sum();
        double avg = weightedSum / totalQty;
        return avg > 0 ? avg : null;
    }

    private static Double weightedAverageTradeCost(List<BatchResponse> batches) {
        List<BatchResponse> usable = batches.stream().filter(ProductInventoryService::isUsable).toList();
        double totalQty = usable.stream().mapToDouble(BatchResponse::quantity).sum();
        if (totalQty <= 0) {
            return usable.stream().mapToDouble(BatchResponse::tradeCost).filter(c -> c > 0).average().orElse(0) > 0
                    ? usable.stream().mapToDouble(BatchResponse::tradeCost).filter(c -> c > 0).average().getAsDouble()
                    : null;
        }
        double weightedSum = usable.stream().mapToDouble(b -> b.quantity() * b.tradeCost()).sum();
        double avg = weightedSum / totalQty;
        return avg > 0 ? avg : null;
    }

    private InventoryApiSchemas.ProductDetail buildDetail(
            String tenantId, InventoryItemResponse it, List<BatchResponse> batches,
            InventoryApiSchemas.BatchListResponse batchList, double availableQuantity, Double sellingPrice) {
        double total = batches.stream().filter(ProductInventoryService::isUsable).mapToDouble(BatchResponse::quantity).sum();
        Map<String, Object> ex = mergedExtras(it);
        String cat = ex.containsKey("category")
                ? ex.get("category").toString()
                : resolveCategory(it, ex);
        Enums.UnitOfMeasure uom =
                ex.containsKey("unit_of_measure")
                        ? ItemExtrasCodec.uom(ex.get("unit_of_measure").toString())
                        : Enums.UnitOfMeasure.fromItemUom(it.unit());
        double reorder = ex.containsKey("reorder_level")
                ? toDouble(ex.get("reorder_level"))
                : it.reorderLevel();
        double maxStock = ex.containsKey("maximum_stock")
                ? toDouble(ex.get("maximum_stock"))
                : it.maxStock();
        InventoryApiSchemas.SpecialRequirements sr = ItemExtrasCodec.specials(ex);
        List<Enums.ProductStatus> statuses = computeStatuses(total, reorder, it.isControlled(), batches);
        List<InventoryApiSchemas.StockAlert> alerts = buildAlerts(total, reorder, batches);
        UUID mfrId = null;
        if (ex.get("manufacturer_id") != null) {
            try {
                mfrId = UUID.fromString(ex.get("manufacturer_id").toString());
            } catch (Exception ignored) {
            }
        }
        InventoryApiSchemas.Manufacturer mfr = mfrId != null ? ManufacturersCatalog.byId(mfrId) : null;
        double maxVal = batches.stream().filter(ProductInventoryService::isUsable).mapToDouble(b -> b.quantity() * b.cost()).sum();
        double curr = total;
        double reorderQty = Math.max(0, maxStock - curr);
        Double unitPrice = weightedAverageUnitCost(batches);
        Double tradeCostAvg = weightedAverageTradeCost(batches);
        return new InventoryApiSchemas.ProductDetail(
                StableEntityIds.itemId(tenantId, it.id()),
                it.name(),
                ItemExtrasCodec.displayGenericName(it.genericName(), ex),
                cat,
                total,
                availableQuantity,
                uom,
                batches.size(),
                statuses,
                null,
                "",
                "",
                mfr,
                str(ex.get("ppb_code")),
                str(ex.get("ndc_code")),
                ItemExtrasCodec.reg(str(ex.get("regulatory_status"))),
                str(ex.get("strength")),
                str(ex.get("dosage_form")),
                str(ex.get("additional_notes")),
                str(ex.get("terminology_source")),
                str(ex.get("terminology_id")),
                reorder,
                maxStock,
                sr,
                curr,
                maxVal,
                "KES",
                reorderQty,
                alerts,
                batchList,
                unitPrice,
                tradeCostAvg,
                sellingPrice);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }

    private List<InventoryApiSchemas.StockAlert> buildAlerts(double total, double reorder, List<BatchResponse> batches) {
        List<InventoryApiSchemas.StockAlert> a = new ArrayList<>();
        if (total <= 0) {
            a.add(new InventoryApiSchemas.StockAlert("out_of_stock", "No stock available", "error"));
        } else if (total < reorder) {
            a.add(new InventoryApiSchemas.StockAlert(
                    "low_stock", "Current stock is below reorder level", "warning"));
        }
        boolean expiring = batches.stream().anyMatch(b -> "expiring-soon".equals(b.status()));
        if (expiring) {
            a.add(new InventoryApiSchemas.StockAlert("expiring_soon", "At least one batch expires within 90 days", "warning"));
        }
        return a;
    }

    private List<Enums.ProductStatus> computeStatuses(
            double total, double reorder, boolean controlled, List<BatchResponse> batches) {
        List<Enums.ProductStatus> s = new ArrayList<>();
        if (total <= 0) s.add(Enums.ProductStatus.out_of_stock);
        else if (total < reorder) s.add(Enums.ProductStatus.low_stock);
        else s.add(Enums.ProductStatus.available);
        if (controlled) s.add(Enums.ProductStatus.controlled);
        if (batches.stream().anyMatch(b -> "expiring-soon".equals(b.status()))) {
            s.add(Enums.ProductStatus.expiring_soon);
        }
        return s;
    }

    private InventoryApiSchemas.Batch toApiBatch(String tenantId, String itemCode, BatchResponse b) {
        return toApiBatch(tenantId, itemCode, b, null);
    }

    private InventoryApiSchemas.Batch toApiBatch(
            String tenantId, String itemCode, BatchResponse b, String category) {
        return new InventoryApiSchemas.Batch(
                StableEntityIds.batchId(tenantId, b.id()),
                StableEntityIds.itemId(tenantId, itemCode),
                b.batchNumber(),
                mappedBatchEnum(b.status()),
                b.quantity(),
                Enums.UnitOfMeasure.units,
                b.receivedDate(),
                b.expiryDate(),
                b.supplier(),
                b.tradeCost() > 0 ? b.tradeCost() : null,
                b.cost(),
                b.quantity() * b.cost(),
                "KES",
                b.location(),
                b.branch(),
                b.grn(),
                b.manufacturer(),
                category,
                "",
                "");
    }

    private static String resolveCategory(InventoryItemResponse it, Map<String, Object> ex) {
        if (ex.containsKey("category") && ex.get("category") != null) {
            return ex.get("category").toString();
        }
        return it.category();
    }

    private static Enums.BatchStatus mappedBatchEnum(String legacy) {
        if (legacy == null) return Enums.BatchStatus.available;
        return switch (legacy) {
            case "expired" -> Enums.BatchStatus.expired;
            case "quarantined" -> Enums.BatchStatus.quarantine;
            case "recalled" -> Enums.BatchStatus.recalled;
            case "expiring-soon", "available" -> Enums.BatchStatus.available;
            default -> Enums.BatchStatus.active;
        };
    }

    private static String mapUom(Enums.UnitOfMeasure u) {
        return switch (u) {
            case tablets, capsules -> "Nos";
            case vials, ampoules, bottles -> "Nos";
            default -> "Nos";
        };
    }

    private static int normalizePage(int page) {
        return page < 1 ? 1 : page;
    }

    private static int clampLimit(int limit) {
        if (limit < 1) return 10;
        return Math.min(limit, 100);
    }

    private static long calcTotalPages(long total, int limit) {
        if (limit <= 0) return 0;
        return (long) Math.ceil((double) total / (double) limit);
    }

    private static boolean matchesSearch(
            InventoryApiSchemas.ProductSummary p, String search, Map<String, Object> mergedExtras) {
        if (!StringUtils.hasText(search)) return true;

        // Build searchable corpus: product name, generic name, strength, dosage form, codes
        List<String> corpus = new ArrayList<>();
        corpus.add(p.productName());
        corpus.add(p.genericName());
        if (mergedExtras != null) {
            for (String key : List.of("strength", "dosage_form", "ppb_code", "ndc_code", "terminology_id")) {
                Object v = mergedExtras.get(key);
                if (v != null && !v.toString().isBlank()) corpus.add(v.toString());
            }
        }

        // All query tokens must match something in the corpus (AND logic)
        String[] tokens = search.strip().toLowerCase(Locale.ROOT).split("\\s+");
        for (String token : tokens) {
            if (!corpusMatchesToken(corpus, token)) return false;
        }
        return true;
    }

    private static boolean corpusMatchesToken(List<String> corpus, String token) {
        for (String field : corpus) {
            if (field == null || field.isBlank()) continue;
            String f = field.toLowerCase(Locale.ROOT);
            // Fast path: substring match covers partial typing and code lookups
            if (f.contains(token)) return true;
            // Fuzzy path: word-level edit distance for typo tolerance (min 4 chars)
            if (token.length() >= 4) {
                for (String word : f.split("[\\s\\-/,]+")) {
                    if (word.length() >= 3 && editDistance(word, token) <= fuzzyThreshold(token)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int fuzzyThreshold(String token) {
        // Allow 1 edit for tokens up to 7 chars, 2 edits for longer words
        return token.length() <= 7 ? 1 : 2;
    }

    private static int editDistance(String a, String b) {
        int m = a.length(), n = b.length();
        // Early exit: length difference alone exceeds any reasonable threshold
        if (Math.abs(m - n) > 2) return Math.abs(m - n);
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                curr[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1]
                        : 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    private static boolean duplicatePpbOrNdc(List<InventoryItemResponse> items, InventoryApiSchemas.CreateProductRequest req) {
        if (!StringUtils.hasText(req.ppbCode()) && !StringUtils.hasText(req.ndcCode())) return false;
        for (InventoryItemResponse it : items) {
            Map<String, Object> ex = mergedExtras(it);
            if (req.ppbCode() != null
                    && req.ppbCode().equals(Optional.ofNullable(ex.get("ppb_code")).map(Object::toString).orElse(null))) {
                return true;
            }
            if (req.ndcCode() != null
                    && req.ndcCode().equals(Optional.ofNullable(ex.get("ndc_code")).map(Object::toString).orElse(null))) {
                return true;
            }
        }
        return false;
    }

    private static void validateCreate(InventoryApiSchemas.CreateProductRequest r) {
        if (r == null
                || !StringUtils.hasText(r.productName())
                || !StringUtils.hasText(r.genericName())
                || !StringUtils.hasText(r.category())
                || r.unitOfMeasure() == null
                || r.reorderLevel() == null
                || r.maximumStock() == null) {
            throw new ke.co.safaricom.pims.inventory.exception.ServiceValidationException(
                    "Missing required fields for product creation");
        }
    }

    private static void validateBatch(InventoryApiSchemas.CreateBatchRequest b) {
        if (b == null) {
            throw new ke.co.safaricom.pims.inventory.exception.ServiceValidationException(
                    ErrorCode.VALIDATION_ERROR, "Batch payload is required");
        }
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(b.batchNumber())) {
            missing.add("batch_number");
        }
        if (!StringUtils.hasText(b.expiryDate())) {
            missing.add("expiry_date");
        }
        if (b.quantity() == null) {
            missing.add("quantity");
        }
        if (b.unitCost() == null) {
            missing.add("unit_cost");
        }
        if (!StringUtils.hasText(b.storageLocation())) {
            missing.add("storage_location");
        }
        if (!missing.isEmpty()) {
            throw new ke.co.safaricom.pims.inventory.exception.ServiceValidationException(
                    ErrorCode.VALIDATION_ERROR,
                    "Missing or invalid batch fields: " + String.join(", ", missing));
        }
    }
}
