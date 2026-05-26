package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.CreateStockAdjustmentRequest;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.api.dto.StockAdjustmentResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.mapper.InventoryMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryService {

    private static final String PARAM_FIELDS = "fields";
    private static final String PARAM_FILTERS = "filters";
    private static final String DOCTYPE_STOCK_ENTRY = "Stock Entry";

    private static final String ITEM_FIELDS =
            "[\"name\",\"item_name\",\"item_group\",\"stock_uom\",\"description\",\"disabled\",\"reorder_levels\"]";
    private static final String BATCH_FIELDS =
            "[\"name\",\"batch_id\",\"item\",\"expiry_date\",\"manufacturing_date\",\"supplier\",\"disabled\"]";
    private static final String STOCK_ENTRY_FIELDS =
            "[\"name\",\"purpose\",\"posting_date\",\"remarks\",\"from_warehouse\",\"to_warehouse\",\"owner\",\"docstatus\"]";

    private final ErpNextTenantRouter router;
    private final InventoryMapper mapper;

    public InventoryService(ErpNextTenantRouter router, InventoryMapper mapper) {
        this.router = router;
        this.mapper = mapper;
    }

    // ---- Items --------------------------------------------------------------

    public Mono<List<InventoryItemResponse>> listItems(String tenantId) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, ITEM_FIELDS);
        params.put(PARAM_FILTERS, "[[\"disabled\",\"=\",0]]");

        return router.getList(tenantId, "Item", params, listResponseClass())
                .map(response -> response.data().stream()
                        .map(mapper::toItemResponse)
                        .toList());
    }

    public Mono<InventoryItemResponse> getItem(String tenantId, String itemId) {
        return router.getOne(tenantId, "Item", itemId, singleResponseClass())
                .map(response -> mapper.toItemResponse(response.data()));
    }

    // ---- Batches ------------------------------------------------------------

    public Mono<List<BatchResponse>> listBatches(String tenantId) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, BATCH_FIELDS);
        params.put(PARAM_FILTERS, "[[\"disabled\",\"=\",0]]");

        return router.getList(tenantId, "Batch", params, listResponseClass())
                .map(response -> response.data().stream()
                        .map(mapper::toBatchResponse)
                        .toList());
    }

    // ---- Stock Adjustments --------------------------------------------------

    public Mono<List<StockAdjustmentResponse>> listAdjustments(String tenantId) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, STOCK_ENTRY_FIELDS);
        params.put(PARAM_FILTERS, "[[\"purpose\",\"in\",\"Material Receipt,Material Issue\"]]");

        return router.getList(tenantId, DOCTYPE_STOCK_ENTRY, params, listResponseClass())
                .map(response -> response.data().stream()
                        .map(mapper::toAdjustmentResponse)
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

        return router.create(tenantId, DOCTYPE_STOCK_ENTRY, body, singleResponseClass())
                .map(response -> mapper.toAdjustmentResponse(response.data()));
    }

    // ---- ERPNext body helpers -----------------------------------------------

    private Map<String, Object> buildStockEntryItem(CreateStockAdjustmentRequest req) {
        Map<String, Object> item = new HashMap<>();
        item.put("item_code", req.itemCode());
        item.put("qty", req.quantity());
        item.put("t_warehouse", req.warehouse());
        if (req.batchNo() != null) item.put("batch_no", req.batchNo());
        return item;
    }

    // ---- raw-type cast helpers (Jackson can't infer generic records at runtime) ---

    @SuppressWarnings("unchecked")
    private static Class<ErpNextListResponse<ErpNextDoc>> listResponseClass() {
        return (Class<ErpNextListResponse<ErpNextDoc>>) (Class<?>) ErpNextListResponse.class;
    }

    @SuppressWarnings("unchecked")
    private static Class<ErpNextSingleResponse<ErpNextDoc>> singleResponseClass() {
        return (Class<ErpNextSingleResponse<ErpNextDoc>>) (Class<?>) ErpNextSingleResponse.class;
    }
}
