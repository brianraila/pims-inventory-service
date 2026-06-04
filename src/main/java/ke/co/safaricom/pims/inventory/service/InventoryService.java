package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.CreateStockAdjustmentRequest;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.api.dto.StockAdjustmentResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
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

@Service
public class InventoryService {

    private static final String PARAM_FIELDS = "fields";
    private static final String PARAM_FILTERS = "filters";
    private static final String DOCTYPE_STOCK_ENTRY = "Stock Entry";

    private static final String ITEM_FIELDS =
            "[\"name\",\"item_name\",\"item_group\",\"stock_uom\",\"description\",\"disabled\",\"reorder_levels\"]";
    private static final String BATCH_FIELDS =
            "[\"name\",\"batch_id\",\"item\",\"expiry_date\",\"manufacturing_date\",\"supplier\",\"disabled\",\"batch_qty\"]";
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

        return router.create(tenantId, DOCTYPE_STOCK_ENTRY, body, SINGLE_TYPE)
                .map(response -> mapper.toAdjustmentResponse(response.data()));
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
}
