package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.CreatePurchaseOrderRequest;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.api.dto.PurchaseOrderResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.mapper.PurchaseOrderMapper;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PurchaseOrderService {

    private static final String DOCTYPE_PURCHASE_ORDER = "Purchase Order";
    private static final String PARAM_FIELDS = "fields";

    private static final String PO_FIELDS =
            "[\"name\",\"supplier\",\"transaction_date\",\"schedule_date\",\"status\",\"grand_total\"]";

    private final ErpNextTenantRouter router;
    private final PurchaseOrderMapper mapper;
    private final InventoryService inventoryService;

    public PurchaseOrderService(ErpNextTenantRouter router, PurchaseOrderMapper mapper,
                                InventoryService inventoryService) {
        this.router = router;
        this.mapper = mapper;
        this.inventoryService = inventoryService;
    }

    public Mono<List<PurchaseOrderResponse>> listPurchaseOrders(String tenantId, String supplier) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, PO_FIELDS);
        params.put("order_by", "transaction_date desc");
        if (supplier != null && !supplier.isBlank()) {
            String s = supplier.replace("\"", "").trim();
            params.put("filters", "[[\"supplier\",\"=\",\"" + s + "\"]]");
        }

        return router.getList(tenantId, DOCTYPE_PURCHASE_ORDER, params, LIST_TYPE)
                .map(response -> response.data().stream()
                        .map(mapper::toResponse)
                        .toList());
    }

    public Mono<PurchaseOrderResponse> createPurchaseOrder(String tenantId, CreatePurchaseOrderRequest request) {
        String transactionDate = request.transactionDate() != null
                ? request.transactionDate() : LocalDate.now().toString();
        // ERPNext requires a delivery/required-by date on each PO line; fall back to the order date.
        String scheduleDate = request.scheduleDate() != null ? request.scheduleDate() : transactionDate;

        // The frontend sends stable product ids (StableEntityIds), not raw ERPNext item codes —
        // resolve them the same way sales orders do, else ERPNext rejects "Item Code not found".
        return inventoryService.listItems(tenantId).flatMap(allItems -> {
            List<Map<String, Object>> erpItems = new ArrayList<>();
            for (CreatePurchaseOrderRequest.LineItem line : request.items()) {
                InventoryItemResponse match = allItems.stream()
                        .filter(it -> StableEntityIds.itemId(tenantId, it.id()).toString().equals(line.itemCode()))
                        .findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Product not found: " + line.itemCode()));
                Map<String, Object> item = new HashMap<>();
                item.put("item_code", match.id());
                item.put("item_name", match.name());
                item.put("qty", line.qty());
                item.put("schedule_date", scheduleDate);
                if (line.rate() != null) {
                    item.put("rate", line.rate());
                    item.put("allow_zero_valuation_rate", 1);
                }
                erpItems.add(item);
            }

            Map<String, Object> body = new HashMap<>();
            body.put("doctype", DOCTYPE_PURCHASE_ORDER);
            body.put("supplier", request.supplier());
            body.put("transaction_date", transactionDate);
            body.put("schedule_date", scheduleDate);
            if (request.notes() != null) body.put("remarks", request.notes());
            body.put("items", erpItems);

            return router.create(tenantId, DOCTYPE_PURCHASE_ORDER, body, SINGLE_TYPE)
                    .map(response -> mapper.toResponse(response.data()));
        });
    }

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
}
