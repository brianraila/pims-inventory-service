package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.CreatePurchaseOrderRequest;
import ke.co.safaricom.pims.inventory.api.dto.PurchaseOrderResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.mapper.PurchaseOrderMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
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

    public PurchaseOrderService(ErpNextTenantRouter router, PurchaseOrderMapper mapper) {
        this.router = router;
        this.mapper = mapper;
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
        Map<String, Object> body = new HashMap<>();
        body.put("doctype", DOCTYPE_PURCHASE_ORDER);
        body.put("supplier", request.supplier());
        body.put("transaction_date", request.transactionDate() != null
                ? request.transactionDate() : LocalDate.now().toString());
        if (request.scheduleDate() != null) body.put("schedule_date", request.scheduleDate());
        if (request.notes() != null) body.put("remarks", request.notes());
        body.put("items", request.items().stream().map(this::toErpLineItem).toList());

        return router.create(tenantId, DOCTYPE_PURCHASE_ORDER, body, SINGLE_TYPE)
                .map(response -> mapper.toResponse(response.data()));
    }

    private Map<String, Object> toErpLineItem(CreatePurchaseOrderRequest.LineItem line) {
        Map<String, Object> item = new HashMap<>();
        item.put("item_code", line.itemCode());
        item.put("qty", line.qty());
        if (line.rate() != null) item.put("rate", line.rate());
        return item;
    }

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
}
