package ke.co.safaricom.pims.inventory.web.service;

import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.CreateStockAdjustmentRequest;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.api.dto.StockAdjustmentResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import org.springframework.core.ParameterizedTypeReference;
import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.mapper.InventoryMapper;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.util.ItemExtrasCodec;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
import ke.co.safaricom.pims.inventory.web.util.ManufacturersCatalog;
import ke.co.safaricom.pims.inventory.web.util.TerminologyStub;
import ke.co.safaricom.pims.inventory.service.InventoryService;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProductInventoryService {

    private final ErpNextTenantRouter router;
    private final InventoryMapper inventoryMapper;
    private final InventoryService inventoryService;
    private final ProductDraftMemoryStore drafts;

    public ProductInventoryService(
            ErpNextTenantRouter router,
            InventoryMapper inventoryMapper,
            InventoryService inventoryService,
            ProductDraftMemoryStore drafts) {
        this.router = router;
        this.inventoryMapper = inventoryMapper;
        this.inventoryService = inventoryService;
        this.drafts = drafts;
    }

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};

    public Mono<InventoryApiSchemas.ProductListResponse> listProducts(
            String tenantId,
            int page,
            int limit,
            String search,
            Enums.ProductCategory category,
            Enums.ProductStatus statusFilter,
            UUID manufacturerIdFilter) {
        return listItemsEnriched(tenantId)
                .zipWith(inventoryService.listBatches(tenantId))
                .map(tuple -> {
                    List<InventoryItemResponse> items = tuple.getT1();
                    List<BatchResponse> batches = tuple.getT2();
                    Map<String, List<BatchResponse>> byItem =
                            batches.stream().collect(Collectors.groupingBy(BatchResponse::productId));

                    List<InventoryApiSchemas.ProductSummary> rows = new ArrayList<>();
                    for (InventoryItemResponse it : items) {
                        List<BatchResponse> itemBatches = byItem.getOrDefault(it.id(), List.of());
                        Map<String, Object> ex = mergedExtras(it);
                        InventoryApiSchemas.ProductSummary p = toSummary(tenantId, it, itemBatches);
                        if (!matchesSearch(p, search, ex)) continue;
                        if (category != null && p.category() != category) continue;
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

                    rows.sort(Comparator.comparing(InventoryApiSchemas.ProductSummary::productName, String.CASE_INSENSITIVE_ORDER));
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
                Mono.error(new ResourceNotFoundException("Draft not found")));
    }

    public Mono<InventoryApiSchemas.ProductDraft> patchDraft(String tenantId, UUID draftId, InventoryApiSchemas.ProductDraftRequest req) {
        return Mono.fromCallable(() -> drafts.update(tenantId, draftId, req));
    }

    public Mono<InventoryApiSchemas.ProductDetail> getProduct(String tenantId, UUID productId) {
        return listItemsEnriched(tenantId)
                .zipWith(inventoryService.listBatches(tenantId))
                .map(tuple -> {
                    InventoryItemResponse item =
                            tuple.getT1().stream().filter(it -> StableEntityIds.itemId(tenantId, it.id()).equals(productId)).findFirst()
                                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
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
                    return buildDetail(tenantId, item, batches, bl);
                });
    }

    public Mono<InventoryApiSchemas.ProductDetail> createProduct(String tenantId, InventoryApiSchemas.CreateProductRequest req) {
        validateCreate(req);
        return inventoryService.listItems(tenantId).flatMap(existing -> {
            if (duplicatePpbOrNdc(existing, req)) {
                return Mono.error(new ConflictException("Duplicate PPB code or NDC already exists"));
            }
            Map<String, Object> body = new HashMap<>();
            String itemCode = "PIMS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            body.put("doctype", "Item");
            body.put("item_code", itemCode);
            body.put("item_name", req.productName());
            body.put("item_group", req.category() != null ? req.category().name() : "Products");
            body.put("stock_uom", req.unitOfMeasure() != null ? mapUom(req.unitOfMeasure()) : "Nos");
            body.put("is_stock_item", 1);
            body.put("has_batch_no", 1);
            body.put("description", ItemExtrasCodec.embed("", req));
            applyCreateCustomFields(body, req);

            return router
                    .create(tenantId, "Item", body, SINGLE_TYPE)
                    .flatMap(created -> chainInitialBatches(tenantId, itemCode, req))
                    .then(getProduct(tenantId, StableEntityIds.itemId(tenantId, itemCode)));
        });
    }

    public Mono<InventoryApiSchemas.ProductDetail> updateProduct(String tenantId, UUID productId, InventoryApiSchemas.UpdateProductRequest u) {
        return resolveItemName(tenantId, productId)
                .flatMap(itemName -> router
                        .getOne(tenantId, "Item", itemName, SINGLE_TYPE)
                        .flatMap(one -> {
                            ErpNextDoc doc = one.data();
                            Map<String, Object> body = new HashMap<>();
                            body.put("doctype", "Item");
                            body.put("name", doc.name());
                            body.put("item_code", doc.name());
                            body.put("item_name", u.productName() != null ? u.productName() : doc.itemName());
                            body.put("item_group", u.category() != null ? u.category().name() : doc.itemGroup());
                            body.put("stock_uom", u.unitOfMeasure() != null ? mapUom(u.unitOfMeasure()) : doc.stockUom());
                            populateCustomFieldsFromDoc(body, doc);
                            overlayUpdateCustomFields(body, u);
                            String desc = ItemExtrasCodec.mergeUpdate(
                                    Optional.ofNullable(doc.description()).orElse(""), u);
                            body.put("description", desc);
                            return router.replace(tenantId, "Item", doc.name(), body, SINGLE_TYPE);
                        })
                        .then(getProduct(tenantId, productId)));
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
                                    Mono.error(new ResourceNotFoundException("Batch not found")));
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
                        return Mono.error(new ResourceNotFoundException("Batch not found"));
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
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Terminology product not found")));
    }

    // -------------------------------------------------------------------------

    public Mono<InventoryApiSchemas.Batch> addBatchJsonReturn(String tenantId, UUID productId, InventoryApiSchemas.CreateBatchRequest req) {
        validateBatch(req);
        return resolveItemName(tenantId, productId).flatMap(itemCode -> addBatchRaw(tenantId, itemCode, req)
                .then(lastCreatedBatchForItem(tenantId, itemCode, req.batchNumber())));
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
        return router.create(tenantId, "Stock Entry", entry, SINGLE_TYPE).then();
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
                            Mono.error(new ResourceNotFoundException("Batch not persisted")));
                });
    }

    private Mono<String> resolveItemName(String tenantId, UUID productId) {
        return inventoryService
                .listItems(tenantId)
                .map(list -> list.stream()
                        .filter(it -> StableEntityIds.itemId(tenantId, it.id()).equals(productId))
                        .map(InventoryItemResponse::id)
                        .findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found")));
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

    private InventoryApiSchemas.ProductSummary toSummary(String tenantId, InventoryItemResponse it, List<BatchResponse> batches) {
        double total = batches.stream().filter(ProductInventoryService::isUsable).mapToDouble(BatchResponse::quantity).sum();
        Map<String, Object> ex = mergedExtras(it);
        String genericDisplay = ItemExtrasCodec.displayGenericName(it.genericName(), ex);
        Enums.UnitOfMeasure uom = Enums.UnitOfMeasure.fromItemUom(it.unit());
        Enums.ProductCategory cat = Enums.ProductCategory.looseValueOf(it.category());
        List<Enums.ProductStatus> statuses = computeStatuses(total, it.reorderLevel(), it.isControlled(), batches);
        return new InventoryApiSchemas.ProductSummary(
                StableEntityIds.itemId(tenantId, it.id()),
                it.name(),
                genericDisplay,
                cat,
                total,
                uom,
                batches.size(),
                statuses,
                null,
                "",
                "");
    }

    private InventoryApiSchemas.ProductDetail buildDetail(
            String tenantId, InventoryItemResponse it, List<BatchResponse> batches, InventoryApiSchemas.BatchListResponse batchList) {
        double total = batches.stream().filter(ProductInventoryService::isUsable).mapToDouble(BatchResponse::quantity).sum();
        Map<String, Object> ex = mergedExtras(it);
        Enums.ProductCategory cat =
                ex.containsKey("category")
                        ? Enums.ProductCategory.looseValueOf(ex.get("category").toString())
                        : Enums.ProductCategory.looseValueOf(it.category());
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
        return new InventoryApiSchemas.ProductDetail(
                StableEntityIds.itemId(tenantId, it.id()),
                it.name(),
                ItemExtrasCodec.displayGenericName(it.genericName(), ex),
                cat,
                total,
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
                batchList);
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
                null,
                b.cost(),
                b.quantity() * b.cost(),
                "KES",
                b.location(),
                b.branch(),
                b.grn(),
                b.manufacturer(),
                Enums.ProductCategory.Other,
                "",
                "");
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
        String q = search.strip().toLowerCase(Locale.ROOT);
        if (p.productName().toLowerCase(Locale.ROOT).contains(q)
                || p.genericName().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (mergedExtras == null || mergedExtras.isEmpty()) return false;
        for (String key : List.of("ppb_code", "ndc_code", "terminology_id")) {
            Object v = mergedExtras.get(key);
            if (v != null && v.toString().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
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
                || r.category() == null
                || r.unitOfMeasure() == null
                || r.reorderLevel() == null
                || r.maximumStock() == null) {
            throw new ke.co.safaricom.pims.inventory.exception.ServiceValidationException(
                    "Missing required fields for product creation");
        }
    }

    private static void validateBatch(InventoryApiSchemas.CreateBatchRequest b) {
        if (b == null
                || !StringUtils.hasText(b.batchNumber())
                || !StringUtils.hasText(b.expiryDate())
                || b.quantity() == null
                || b.unitCost() == null
                || !StringUtils.hasText(b.storageLocation())) {
            throw new IllegalArgumentException("Invalid batch payload");
        }
    }
}
