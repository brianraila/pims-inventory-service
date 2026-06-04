package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.config.ErpNextProperties;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.web.model.SalesOrderSchemas;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SalesOrderService {

    private static final String DOCTYPE = "Sales Invoice";
    private static final String DEFAULT_CUSTOMER = "Walk-in Customer";
    private static final String CURRENCY = "KES";

    private static final String SI_LIST_FIELDS =
            "[\"name\",\"customer\",\"posting_date\",\"grand_total\",\"status\",\"docstatus\",\"currency\",\"creation\"]";

    private final ErpNextTenantRouter router;
    private final InventoryService inventoryService;
    private final ErpNextProperties properties;

    public SalesOrderService(ErpNextTenantRouter router,
                              InventoryService inventoryService,
                              ErpNextProperties properties) {
        this.router = router;
        this.inventoryService = inventoryService;
        this.properties = properties;
    }

    // ---- Create draft -------------------------------------------------------

    public Mono<SalesOrderSchemas.OrderResponse> createDraft(
            String tenantId, SalesOrderSchemas.CreateOrderRequest req) {
        return inventoryService.listItems(tenantId).flatMap(allItems -> {
            List<Map<String, Object>> erpItems = resolveLineItems(tenantId, req.items(), allItems);
            String customer = resolveCustomer(req.customerName());
            Map<String, Object> body = buildInvoiceBody(customer, erpItems, req.prescriptionId(), 0);
            return router.create(tenantId, DOCTYPE, body, SINGLE_TYPE)
                    .map(resp -> toOrderResponse(resp.data(), erpItems));
        });
    }

    // ---- Submit draft -------------------------------------------------------

    public Mono<SalesOrderSchemas.OrderResponse> submitOrder(
            String tenantId, String orderId, SalesOrderSchemas.SubmitOrderRequest req) {
        // Call frappe.client.submit with the document name — Frappe loads it server-side and submits
        Map<String, Object> submitBody = new HashMap<>();
        submitBody.put("doc", Map.of("doctype", DOCTYPE, "name", orderId));

        // If payment notes provided, update remarks before submitting
        Mono<Void> prePatch = Mono.empty();
        if (req != null && (StringUtils.hasText(req.paymentMethod()) || StringUtils.hasText(req.notes()))) {
            prePatch = patchRemarks(tenantId, orderId, req);
        }

        return prePatch
                .then(router.callMethod(tenantId, "frappe.client.submit", submitBody, SINGLE_TYPE))
                .map(resp -> toOrderResponse(resp.data(), null));
    }

    private Mono<Void> patchRemarks(String tenantId, String orderId, SalesOrderSchemas.SubmitOrderRequest req) {
        return router.getOne(tenantId, DOCTYPE, orderId, SINGLE_TYPE).flatMap(resp -> {
            ErpNextDoc doc = resp.data();
            String remarks = buildPaymentRemarks(req);
            Map<String, Object> body = new HashMap<>();
            body.put("doctype", DOCTYPE);
            body.put("name", orderId);
            body.put("customer", doc.customer());
            body.put("posting_date", doc.postingDate());
            body.put("currency", CURRENCY);
            body.put("update_stock", 1);
            body.put("is_pos", 0);
            if (doc.items() != null) body.put("items", doc.items());
            body.put("remarks", remarks);
            return router.replace(tenantId, DOCTYPE, orderId, body, SINGLE_TYPE).then();
        });
    }

    // ---- List ---------------------------------------------------------------

    public Mono<SalesOrderSchemas.OrderListResponse> listOrders(
            String tenantId, int page, int limit, String status, String from, String to) {
        Map<String, String> params = new HashMap<>();
        params.put("fields", SI_LIST_FIELDS);
        params.put("order_by", "creation desc");
        List<String> filters = new ArrayList<>();
        filters.add("[\"docstatus\",\"!=\",2]");
        if (StringUtils.hasText(status)) filters.add("[\"status\",\"=\",\"" + status + "\"]");
        if (StringUtils.hasText(from)) filters.add("[\"posting_date\",\">=\",\"" + from + "\"]");
        if (StringUtils.hasText(to)) filters.add("[\"posting_date\",\"<=\",\"" + to + "\"]");
        if (!filters.isEmpty()) {
            params.put("filters", "[" + String.join(",", filters) + "]");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safePage = Math.max(1, page);
        params.put("limit_page_length", String.valueOf(safeLimit));
        params.put("limit_start", String.valueOf((safePage - 1) * safeLimit));

        return router.getList(tenantId, DOCTYPE, params, LIST_TYPE)
                .map(resp -> {
                    List<SalesOrderSchemas.OrderSummary> rows = resp.data().stream()
                            .map(this::toOrderSummary)
                            .toList();
                    long total = rows.size();
                    var pagination = new ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas.Pagination(
                            safePage, safeLimit, total, (long) Math.ceil((double) total / safeLimit));
                    return new SalesOrderSchemas.OrderListResponse(rows, pagination);
                });
    }

    // ---- Get single ---------------------------------------------------------

    public Mono<SalesOrderSchemas.OrderResponse> getOrder(String tenantId, String orderId) {
        return router.getOne(tenantId, DOCTYPE, orderId, SINGLE_TYPE)
                .map(resp -> toOrderResponse(resp.data(), null));
    }

    // ---- Helpers ------------------------------------------------------------

    private List<Map<String, Object>> resolveLineItems(
            String tenantId,
            List<SalesOrderSchemas.CreateOrderRequest.OrderItem> orderItems,
            List<InventoryItemResponse> allItems) {
        List<Map<String, Object>> erpItems = new ArrayList<>();
        for (SalesOrderSchemas.CreateOrderRequest.OrderItem oi : orderItems) {
            InventoryItemResponse match = allItems.stream()
                    .filter(it -> StableEntityIds.itemId(tenantId, it.id()).equals(oi.productId()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + oi.productId()));
            double amount = oi.quantity() * oi.unitPrice();
            Map<String, Object> line = new HashMap<>();
            line.put("item_code", match.id());
            line.put("item_name", match.name());
            line.put("qty", oi.quantity());
            line.put("rate", oi.unitPrice());
            line.put("amount", amount);
            line.put("warehouse", properties.defaultWarehouse());
            erpItems.add(line);
        }
        return erpItems;
    }

    private Map<String, Object> buildInvoiceBody(
            String customer, List<Map<String, Object>> items, String prescriptionId, int docstatus) {
        Map<String, Object> body = new HashMap<>();
        body.put("doctype", DOCTYPE);
        body.put("customer", customer);
        body.put("posting_date", LocalDate.now().toString());
        body.put("currency", CURRENCY);
        body.put("update_stock", 1);
        body.put("is_pos", 0);
        body.put("docstatus", docstatus);
        body.put("items", items);
        if (StringUtils.hasText(prescriptionId)) {
            body.put("remarks", "Prescription: " + prescriptionId);
        }
        return body;
    }

    private static String resolveCustomer(String customerName) {
        return StringUtils.hasText(customerName) ? customerName : DEFAULT_CUSTOMER;
    }

    private static String buildPaymentRemarks(SalesOrderSchemas.SubmitOrderRequest req) {
        if (req == null) return null;
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(req.paymentMethod())) {
            sb.append("Payment: ").append(req.paymentMethod().toUpperCase());
        }
        if (req.amountReceived() != null) {
            sb.append(" | Received: KES ").append(req.amountReceived());
        }
        if (StringUtils.hasText(req.mpesaPhone())) {
            sb.append(" | Phone: ").append(req.mpesaPhone());
        }
        if (StringUtils.hasText(req.notes())) {
            sb.append(" | ").append(req.notes());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private SalesOrderSchemas.OrderResponse toOrderResponse(
            ErpNextDoc doc, List<Map<String, Object>> fallbackItems) {
        List<SalesOrderSchemas.OrderLineItem> lines = extractLineItems(
                doc.items() != null ? doc.items() : (fallbackItems != null ? fallbackItems : List.of()));
        double subtotal = lines.stream().mapToDouble(SalesOrderSchemas.OrderLineItem::lineTotal).sum();
        double grandTotal = doc.grandTotal() != null ? doc.grandTotal() : subtotal;
        double taxAmount = doc.totalTaxesAndCharges() != null ? doc.totalTaxesAndCharges() : grandTotal - subtotal;
        return new SalesOrderSchemas.OrderResponse(
                doc.name(),
                docStatusLabel(doc.docstatus()),
                doc.customer() != null ? doc.customer() : DEFAULT_CUSTOMER,
                lines,
                subtotal,
                taxAmount,
                grandTotal,
                doc.currency() != null ? doc.currency() : CURRENCY,
                doc.creation());
    }

    private SalesOrderSchemas.OrderSummary toOrderSummary(ErpNextDoc doc) {
        return new SalesOrderSchemas.OrderSummary(
                doc.name(),
                doc.customer() != null ? doc.customer() : DEFAULT_CUSTOMER,
                docStatusLabel(doc.docstatus()),
                doc.grandTotal() != null ? doc.grandTotal() : 0,
                doc.currency() != null ? doc.currency() : CURRENCY,
                doc.creation());
    }

    private static List<SalesOrderSchemas.OrderLineItem> extractLineItems(List<Map<String, Object>> items) {
        return items.stream().map(m -> {
            String code = str(m.get("item_code"));
            String name = str(m.get("item_name"));
            double qty = toDouble(m.get("qty"));
            double rate = toDouble(m.get("rate"));
            double amount = m.containsKey("amount") ? toDouble(m.get("amount")) : qty * rate;
            return new SalesOrderSchemas.OrderLineItem(code, name, qty, rate, amount);
        }).toList();
    }

    private static String docStatusLabel(Integer docstatus) {
        if (docstatus == null) return "draft";
        return switch (docstatus) {
            case 0 -> "draft";
            case 1 -> "submitted";
            case 2 -> "cancelled";
            default -> "unknown";
        };
    }

    private static String str(Object o) { return o != null ? o.toString() : ""; }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0;
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
}
