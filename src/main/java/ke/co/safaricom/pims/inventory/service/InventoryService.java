package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.CreateStockAdjustmentRequest;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.api.dto.StockAdjustmentResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.config.ErpNextProperties;
import ke.co.safaricom.pims.inventory.mapper.InventoryMapper;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private static final String PARAM_FIELDS = "fields";
    private static final String PARAM_FILTERS = "filters";
    private static final String DOCTYPE_STOCK_ENTRY = "Stock Entry";

    private static final String ITEM_FIELDS =
            "[\"name\",\"item_name\",\"item_group\",\"stock_uom\",\"description\",\"disabled\",\"reorder_levels\"]";
    private static final String BATCH_FIELDS =
            "[\"name\",\"batch_id\",\"item\",\"expiry_date\",\"manufacturing_date\"," +
            "\"supplier\",\"disabled\",\"batch_qty\"," +
            "\"custom_pims_unit_cost\",\"custom_pims_trade_cost\"]";
    private static final String STOCK_ENTRY_FIELDS =
            "[\"name\",\"purpose\",\"posting_date\",\"remarks\",\"from_warehouse\",\"to_warehouse\",\"owner\",\"docstatus\"]";

    private final ErpNextTenantRouter router;
    private final InventoryMapper mapper;
    private final ErpNextProperties properties;

    public InventoryService(ErpNextTenantRouter router, InventoryMapper mapper, ErpNextProperties properties) {
        this.router = router;
        this.mapper = mapper;
        this.properties = properties;
    }

    // ---- Items --------------------------------------------------------------

    public Mono<List<InventoryItemResponse>> listItems(String tenantId) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, ITEM_FIELDS);
        params.put(PARAM_FILTERS, "[[\"disabled\",\"=\",0]]");

        return router.getList(tenantId, "Item", params, LIST_TYPE)
                .map(response -> response.data().stream()
                        .map(mapper::toItemResponse)
                        .toList());
    }

    public Mono<InventoryItemResponse> getItem(String tenantId, String itemId) {
        return router.getOne(tenantId, "Item", itemId, SINGLE_TYPE)
                .map(response -> mapper.toItemResponse(response.data()));
    }

    // ---- Stock levels --------------------------------------------------------

    /** Available quantity (actual - reserved) per item code in the given warehouse, from ERPNext's Bin doctype. */
    public Mono<Map<String, Double>> getStockLevels(String tenantId, List<String> itemCodes, String warehouse) {
        if (itemCodes.isEmpty()) return Mono.just(Map.of());

        String codes = itemCodes.stream()
                .map(code -> "\"" + code + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, "[\"item_code\",\"actual_qty\",\"reserved_qty\"]");
        params.put(PARAM_FILTERS, "[[\"item_code\",\"in\"," + codes + "],[\"warehouse\",\"=\",\"" + warehouse + "\"]]");

        return router.getList(tenantId, "Bin", params, LIST_TYPE)
                .map(response -> response.data().stream()
                        .collect(Collectors.toMap(
                                ErpNextDoc::itemCode,
                                doc -> nullToZero(doc.actualQty()) - nullToZero(doc.reservedQty()),
                                (a, b) -> a)));
    }

    private static double nullToZero(Double value) {
        return value != null ? value : 0;
    }

    // ---- Batches ------------------------------------------------------------

    public Mono<List<BatchResponse>> listBatches(String tenantId) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, BATCH_FIELDS);
        params.put(PARAM_FILTERS, "[[\"disabled\",\"=\",0]]");

        return router.getList(tenantId, "Batch", params, LIST_TYPE)
                .map(response -> response.data().stream()
                        .map(mapper::toBatchResponse)
                        .toList());
    }

    // ---- Stock Adjustments --------------------------------------------------

    public Mono<List<StockAdjustmentResponse>> listAdjustments(String tenantId, String itemCode) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, STOCK_ENTRY_FIELDS);
        String filters = itemCode != null && !itemCode.isBlank()
                ? "[[\"Stock Entry Detail\",\"item_code\",\"=\",\"" + itemCode + "\"],[\"purpose\",\"in\",\"Material Receipt,Material Issue\"]]"
                : "[[\"purpose\",\"in\",\"Material Receipt,Material Issue\"]]";
        params.put(PARAM_FILTERS, filters);

        return router.getList(tenantId, DOCTYPE_STOCK_ENTRY, params, LIST_TYPE)
                .map(response -> response.data().stream()
                        .map(doc -> mapper.toAdjustmentResponse(doc, itemCode))
                        .toList());
    }

    public Mono<StockAdjustmentResponse> createAdjustment(String tenantId, CreateStockAdjustmentRequest request) {
        String purpose = "addition".equals(request.type()) ? "Material Receipt" : "Material Issue";
        Map<String, Object> body = new HashMap<>();
        body.put("doctype", DOCTYPE_STOCK_ENTRY);
        body.put("stock_entry_type", purpose);
        body.put("purpose", purpose);
        body.put("remarks", request.reason());
        body.put("items", List.of(buildStockEntryItem(request)));

        // ERPNext only books the quantity into Bin.actual_qty once the Stock Entry is
        // *submitted* — a draft has no stock effect, so chain an immediate submit after create.
        return router.create(tenantId, DOCTYPE_STOCK_ENTRY, body, SINGLE_TYPE)
                .map(ErpNextSingleResponse::data)
                .map(ErpNextDoc::name)
                .flatMap(name -> submitStockEntry(tenantId, name))
                .map(mapper::toAdjustmentResponse);
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
        return router.getOne(tenantId, DOCTYPE_STOCK_ENTRY, name, RAW_SINGLE_TYPE)
                .map(ErpNextSingleResponse::data)
                .flatMap(latest -> {
                    allowZeroValuationRateOnZeroCostItems(latest);
                    Map<String, Object> body = new HashMap<>();
                    body.put("doc", latest);
                    return router.callMethod(tenantId, "frappe.client.submit", body, SUBMIT_TYPE);
                })
                .map(ErpNextMessageResponse::message);
    }

    /**
     * ERPNext blocks submission when a line item has basic_rate=0 unless
     * allow_zero_valuation_rate is explicitly set. This is common for items
     * that carry no purchase cost (e.g. test data or donated stock).
     */
    @SuppressWarnings("unchecked")
    private static void allowZeroValuationRateOnZeroCostItems(Map<String, Object> doc) {
        Object itemsObj = doc.get("items");
        if (!(itemsObj instanceof List<?> rawList)) return;
        for (Object raw : rawList) {
            if (!(raw instanceof Map)) continue;
            Map<String, Object> item = (Map<String, Object>) raw;
            Object rate = item.get("basic_rate");
            boolean isZeroCost;
            if (rate instanceof Number n) {
                isZeroCost = n.doubleValue() == 0.0;
            } else {
                isZeroCost = true;
            }
            if (isZeroCost) {
                item.put("allow_zero_valuation_rate", 1);
            }
        }
    }

    // ---- Metadata lookups ---------------------------------------------------

    public Mono<List<InventoryApiSchemas.SupplierOption>> listSuppliers(String tenantId) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, "[\"name\",\"supplier_name\"]");
        params.put("limit_page_length", "200");
        return router.getList(tenantId, "Supplier", params, LIST_TYPE)
                .map(r -> r.data().stream()
                        .map(d -> new InventoryApiSchemas.SupplierOption(d.name(), d.supplierName()))
                        .toList());
    }

    public Mono<List<InventoryApiSchemas.WarehouseOption>> listWarehouses(String tenantId) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, "[\"name\",\"warehouse_name\"]");
        params.put(PARAM_FILTERS, "[[\"is_group\",\"=\",0]]");
        params.put("limit_page_length", "200");
        return router.getList(tenantId, "Warehouse", params, LIST_TYPE)
                .map(r -> r.data().stream()
                        .map(d -> new InventoryApiSchemas.WarehouseOption(d.name(), d.warehouseName()))
                        .toList());
    }

    public String defaultWarehouse() {
        return properties.defaultWarehouse();
    }

    // ---- ERPNext body helpers -----------------------------------------------

    private Map<String, Object> buildStockEntryItem(CreateStockAdjustmentRequest req) {
        Map<String, Object> item = new HashMap<>();
        item.put("item_code", req.itemCode());
        item.put("qty", req.quantity());
        String warehouse = StringUtils.hasText(req.warehouse()) ? req.warehouse() : properties.defaultWarehouse();
        item.put("t_warehouse", warehouse);
        if (req.batchNo() != null) item.put("batch_no", req.batchNo());
        return item;
    }

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<Map<String, Object>>> RAW_SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};

    // frappe.client.submit is an /api/method/* RPC — Frappe wraps its return value in
    // {"message": ...}, not the {"data": ...} envelope used by /api/resource/* endpoints.
    private static final ParameterizedTypeReference<ErpNextMessageResponse<ErpNextDoc>> SUBMIT_TYPE =
            new ParameterizedTypeReference<>() {};
}
