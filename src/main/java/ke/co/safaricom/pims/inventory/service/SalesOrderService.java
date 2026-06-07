package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.config.ErpNextProperties;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
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

    // ---- ERPNext doctype & defaults ------------------------------------------
    private static final String DOCTYPE       = "Sales Invoice";
    private static final String DEFAULT_CUSTOMER = "Walk-in Customer";
    private static final String CURRENCY      = "KES";

    // ---- ERPNext field keys (avoids SonarLint S1192) ------------------------
    private static final String F_DOCTYPE      = "doctype";
    private static final String F_NAME         = "name";
    private static final String F_CUSTOMER     = "customer";
    private static final String F_POSTING_DATE = "posting_date";
    private static final String F_CURRENCY     = "currency";
    private static final String F_UPDATE_STOCK = "update_stock";
    private static final String F_IS_POS       = "is_pos";
    private static final String F_ITEMS        = "items";
    private static final String F_REMARKS      = "remarks";
    private static final String F_AMOUNT       = "amount";
    private static final String F_ITEM_CODE    = "item_code";
    private static final String F_ITEM_NAME    = "item_name";

    // ---- Query fields -------------------------------------------------------
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
            return ensureStockAvailable(tenantId, erpItems).then(Mono.defer(() -> {
                String customer = resolveCustomer(req.customerName());
                Map<String, Object> body = buildInvoiceBody(customer, erpItems, req.prescriptionId(), 0);
                return router.create(tenantId, DOCTYPE, body, SINGLE_TYPE)
                        .map(resp -> toOrderResponse(resp.data(), erpItems));
            }));
        });
    }

    // ---- Update line items on a draft ---------------------------------------

    public Mono<SalesOrderSchemas.OrderResponse> updateItems(
            String tenantId, String orderId, SalesOrderSchemas.UpdateOrderItemsRequest req) {
        return router.getOne(tenantId, DOCTYPE, orderId, SINGLE_TYPE).flatMap(resp -> {
            ErpNextDoc doc = resp.data();
            if (doc.docstatus() != null && doc.docstatus() != 0) {
                return Mono.error(new ServiceValidationException(
                        "Cannot modify items on a " + docStatusLabel(doc.docstatus()) + " order"));
            }
            return inventoryService.listItems(tenantId).flatMap(allItems -> {
                List<Map<String, Object>> newItems = resolveLineItems(tenantId, req.items(), allItems);
                return ensureStockAvailable(tenantId, newItems).then(Mono.defer(() -> {
                    Map<String, Object> body = buildDraftBody(doc, orderId, newItems);
                    return router.replace(tenantId, DOCTYPE, orderId, body, SINGLE_TYPE)
                            .map(updated -> toOrderResponse(updated.data(), newItems));
                }));
            });
        });
    }

    /** Rejects with {@link ServiceValidationException} if any resolved line exceeds available stock. */
    private Mono<Void> ensureStockAvailable(String tenantId, List<Map<String, Object>> erpItems) {
        List<String> itemCodes = erpItems.stream()
                .map(item -> (String) item.get(F_ITEM_CODE))
                .distinct()
                .toList();
        return inventoryService.getStockLevels(tenantId, itemCodes, properties.defaultWarehouse())
                .flatMap(stockLevels -> {
                    for (Map<String, Object> item : erpItems) {
                        String itemCode = (String) item.get(F_ITEM_CODE);
                        double requested = toDouble(item.get("qty"));
                        double available = stockLevels.getOrDefault(itemCode, 0.0);
                        if (requested > available) {
                            return Mono.error(new ServiceValidationException(
                                    "Insufficient stock for " + item.get(F_ITEM_NAME)
                                            + ": requested " + requested + ", available " + available));
                        }
                    }
                    return Mono.empty();
                });
    }

    // ---- Submit draft -------------------------------------------------------

    public Mono<SalesOrderSchemas.OrderResponse> submitOrder(
            String tenantId, String orderId, SalesOrderSchemas.SubmitOrderRequest req) {
        Mono<Void> prePatch = Mono.empty();
        if (req != null && (StringUtils.hasText(req.paymentMethod()) || StringUtils.hasText(req.notes()))) {
            prePatch = patchRemarks(tenantId, orderId, req);
        }

        // The submit RPC reconstructs its working document purely from the "doc" payload it's
        // handed: frappe.get_doc on a plain dict populates an in-memory doc straight from that
        // dict's own keys, doing no DB load at all. So echoing back only a handful of fields
        // (doctype, name, modified, docstatus) leaves the rest — items, customer, and so on —
        // empty and fails validate(). Fetching the complete current document raw, after any
        // pre-patch (which itself bumps "modified"), both supplies everything validate() needs
        // and naturally satisfies check_if_latest's optimistic-lock comparison on "modified" —
        // exactly what the desk UI does when you click Submit: it posts back its locally-cached
        // copy of the loaded document.
        return prePatch
                .then(router.getOne(tenantId, DOCTYPE, orderId, RAW_SINGLE_TYPE))
                .map(ErpNextSingleResponse::data)
                .flatMap(latest -> {
                    Map<String, Object> submitBody = new HashMap<>();
                    submitBody.put("doc", latest);
                    return router.callMethod(tenantId, "frappe.client.submit", submitBody, SUBMIT_TYPE)
                            .then(router.getOne(tenantId, DOCTYPE, orderId, SINGLE_TYPE));
                })
                .map(resp -> toOrderResponse(resp.data(), null));
    }

    private Mono<Void> patchRemarks(String tenantId, String orderId, SalesOrderSchemas.SubmitOrderRequest req) {
        return router.getOne(tenantId, DOCTYPE, orderId, SINGLE_TYPE).flatMap(resp -> {
            ErpNextDoc doc = resp.data();
            Map<String, Object> body = buildDraftBody(doc, orderId, doc.items());
            body.put(F_REMARKS, buildPaymentRemarks(req));
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
        if (StringUtils.hasText(from))   filters.add("[\"posting_date\",\">=\",\"" + from + "\"]");
        if (StringUtils.hasText(to))     filters.add("[\"posting_date\",\"<=\",\"" + to + "\"]");
        if (!filters.isEmpty()) params.put("filters", "[" + String.join(",", filters) + "]");

        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safePage  = Math.max(1, page);
        params.put("limit_page_length", String.valueOf(safeLimit));
        params.put("limit_start",       String.valueOf((safePage - 1) * safeLimit));

        return router.getList(tenantId, DOCTYPE, params, LIST_TYPE).map(resp -> {
            List<SalesOrderSchemas.OrderSummary> rows = resp.data().stream()
                    .map(this::toOrderSummary).toList();
            long total = rows.size();
            InventoryApiSchemas.Pagination pg = new InventoryApiSchemas.Pagination(
                    safePage, safeLimit, total, (long) Math.ceil((double) total / safeLimit));
            return new SalesOrderSchemas.OrderListResponse(rows, pg);
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
        List<Map<String, Object>> result = new ArrayList<>();
        for (SalesOrderSchemas.CreateOrderRequest.OrderItem oi : orderItems) {
            InventoryItemResponse match = allItems.stream()
                    .filter(it -> StableEntityIds.itemId(tenantId, it.id()).equals(oi.productId()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + oi.productId()));
            Map<String, Object> line = new HashMap<>();
            line.put(F_ITEM_CODE, match.id());
            line.put(F_ITEM_NAME, match.name());
            line.put("qty",       oi.quantity());
            line.put("rate",      oi.unitPrice());
            line.put(F_AMOUNT,    oi.quantity() * oi.unitPrice());
            line.put("warehouse", properties.defaultWarehouse());
            line.put("allow_zero_valuation_rate", 1);
            result.add(line);
        }
        return result;
    }

    private Map<String, Object> buildInvoiceBody(
            String customer, List<Map<String, Object>> items, String prescriptionId, int docstatus) {
        Map<String, Object> body = new HashMap<>();
        body.put(F_DOCTYPE,      DOCTYPE);
        body.put(F_CUSTOMER,     customer);
        body.put(F_POSTING_DATE, LocalDate.now().toString());
        body.put(F_CURRENCY,     CURRENCY);
        body.put(F_UPDATE_STOCK, 1);
        body.put(F_IS_POS,       0);
        body.put("docstatus",    docstatus);
        body.put(F_ITEMS,        items);
        if (StringUtils.hasText(prescriptionId)) {
            body.put(F_REMARKS, "Prescription: " + prescriptionId);
        }
        return body;
    }

    /** Builds the minimal PUT body needed to update a draft without changing its core fields. */
    private Map<String, Object> buildDraftBody(ErpNextDoc doc, String orderId, List<Map<String, Object>> items) {
        Map<String, Object> body = new HashMap<>();
        body.put(F_DOCTYPE,      DOCTYPE);
        body.put(F_NAME,         orderId);
        body.put(F_CUSTOMER,     doc.customer() != null ? doc.customer() : DEFAULT_CUSTOMER);
        body.put(F_POSTING_DATE, doc.postingDate() != null ? doc.postingDate() : LocalDate.now().toString());
        body.put(F_CURRENCY,     CURRENCY);
        body.put(F_UPDATE_STOCK, 1);
        body.put(F_IS_POS,       0);
        if (items != null) body.put(F_ITEMS, items);
        return body;
    }

    private static String resolveCustomer(String name) {
        return StringUtils.hasText(name) ? name : DEFAULT_CUSTOMER;
    }

    private static String buildPaymentRemarks(SalesOrderSchemas.SubmitOrderRequest req) {
        if (req == null) return null;
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(req.paymentMethod())) sb.append("Payment: ").append(req.paymentMethod().toUpperCase());
        if (req.amountReceived() != null) sb.append(" | Received: KES ").append(req.amountReceived());
        if (StringUtils.hasText(req.mpesaPhone())) sb.append(" | Phone: ").append(req.mpesaPhone());
        if (StringUtils.hasText(req.notes())) sb.append(" | ").append(req.notes());
        return sb.isEmpty() ? null : sb.toString();
    }

    private SalesOrderSchemas.OrderResponse toOrderResponse(
            ErpNextDoc doc, List<Map<String, Object>> fallbackItems) {
        List<Map<String, Object>> rawItems = doc.items() != null ? doc.items() : fallbackItems;
        List<SalesOrderSchemas.OrderLineItem> lines = extractLineItems(
                rawItems != null ? rawItems : List.of());
        double subtotal   = lines.stream().mapToDouble(SalesOrderSchemas.OrderLineItem::lineTotal).sum();
        double grandTotal = doc.grandTotal() != null ? doc.grandTotal() : subtotal;
        double taxAmount  = doc.totalTaxesAndCharges() != null
                ? doc.totalTaxesAndCharges() : grandTotal - subtotal;
        return new SalesOrderSchemas.OrderResponse(
                doc.name(),
                docStatusLabel(doc.docstatus()),
                doc.customer() != null ? doc.customer() : DEFAULT_CUSTOMER,
                lines,
                subtotal, taxAmount, grandTotal,
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
            double qty  = toDouble(m.get("qty"));
            double rate = toDouble(m.get("rate"));
            double lineTotal = m.containsKey(F_AMOUNT) ? toDouble(m.get(F_AMOUNT)) : qty * rate;
            return new SalesOrderSchemas.OrderLineItem(
                    str(m.get(F_ITEM_CODE)), str(m.get(F_ITEM_NAME)), qty, rate, lineTotal);
        }).toList();
    }

    private static String docStatusLabel(Integer docstatus) {
        if (docstatus == null) return "draft";
        return switch (docstatus) {
            case 0  -> "draft";
            case 1  -> "submitted";
            case 2  -> "cancelled";
            default -> "unknown";
        };
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0;
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return 0; }
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
