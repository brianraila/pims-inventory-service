package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.CreatePurchaseOrderRequest;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.api.dto.PurchaseOrderDetail;
import ke.co.safaricom.pims.inventory.api.dto.PurchaseOrderResponse;
import ke.co.safaricom.pims.inventory.api.dto.ReceiptSummary;
import ke.co.safaricom.pims.inventory.api.dto.ReceiveStockRequest;
import ke.co.safaricom.pims.inventory.api.dto.UpdatePurchaseOrderRequest;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ConflictException;
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
            "[\"name\",\"supplier\",\"transaction_date\",\"schedule_date\",\"status\",\"total_qty\","
                    + "\"grand_total\",\"per_received\",\"per_billed\"]";

    private static final String DOCTYPE_PURCHASE_RECEIPT = "Purchase Receipt";

    private final ErpNextTenantRouter router;
    private final PurchaseOrderMapper mapper;
    private final InventoryService inventoryService;
    private final ke.co.safaricom.pims.inventory.config.ErpNextProperties properties;

    public PurchaseOrderService(ErpNextTenantRouter router, PurchaseOrderMapper mapper,
                                InventoryService inventoryService,
                                ke.co.safaricom.pims.inventory.config.ErpNextProperties properties) {
        this.router = router;
        this.mapper = mapper;
        this.inventoryService = inventoryService;
        this.properties = properties;
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

    // ---- Detail / update / submit -------------------------------------------

    public Mono<PurchaseOrderDetail> getPurchaseOrder(String tenantId, String id) {
        return router.getOne(tenantId, DOCTYPE_PURCHASE_ORDER, id, SINGLE_TYPE)
                .map(resp -> toDetail(resp.data()));
    }

    public Mono<PurchaseOrderDetail> updatePurchaseOrder(String tenantId, String id,
                                                         UpdatePurchaseOrderRequest req) {
        return router.getOne(tenantId, DOCTYPE_PURCHASE_ORDER, id, SINGLE_TYPE)
                .flatMap(resp -> {
                    ErpNextDoc doc = resp.data();
                    if (doc.docstatus() != null && doc.docstatus() == 1) {
                        return Mono.error(new ConflictException(
                                "This purchase order has been submitted and can no longer be edited."));
                    }
                    String scheduleDate = req.scheduleDate() != null
                            ? req.scheduleDate()
                            : (doc.scheduleDate() != null ? doc.scheduleDate() : LocalDate.now().toString());

                    List<Map<String, Object>> items = new ArrayList<>();
                    for (UpdatePurchaseOrderRequest.Item line : req.items()) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("item_code", line.itemCode());
                        item.put("qty", line.qty());
                        item.put("schedule_date", scheduleDate);
                        if (line.rate() != null) {
                            item.put("rate", line.rate());
                            item.put("allow_zero_valuation_rate", 1);
                        }
                        items.add(item);
                    }

                    Map<String, Object> body = new HashMap<>();
                    body.put("schedule_date", scheduleDate);
                    if (req.notes() != null) body.put("remarks", req.notes());
                    body.put("items", items);

                    return router.replace(tenantId, DOCTYPE_PURCHASE_ORDER, id, body, SINGLE_TYPE)
                            .map(r -> toDetail(r.data()));
                });
    }

    @SuppressWarnings("unchecked")
    public Mono<PurchaseOrderDetail> receiveStock(String tenantId, String id, ReceiveStockRequest req) {
        return router.getOne(tenantId, DOCTYPE_PURCHASE_ORDER, id, SINGLE_TYPE)
                .flatMap(resp -> {
                    ErpNextDoc po = resp.data();
                    if (po.docstatus() == null || po.docstatus() != 1) {
                        return Mono.error(new ConflictException(
                                "Only a submitted purchase order can be received."));
                    }
                    // Rate lookup from the PO lines (by child-row name, then item_code).
                    Map<String, Double> rateByRow = new HashMap<>();
                    Map<String, Double> rateByItem = new HashMap<>();
                    if (po.items() != null) {
                        for (Map<String, Object> row : po.items()) {
                            double rate = num(row.get("rate"));
                            if (row.get("name") != null) rateByRow.put(row.get("name").toString(), rate);
                            if (row.get("item_code") != null) rateByItem.put(row.get("item_code").toString(), rate);
                        }
                    }

                    String warehouse = notBlank(req.warehouse()) ? req.warehouse() : properties.defaultWarehouse();
                    String postingDate = notBlank(req.postingDate()) ? req.postingDate() : LocalDate.now().toString();

                    // Build each receipt line; batch-tracked items need a Serial & Batch Bundle (v15).
                    return reactor.core.publisher.Flux.fromIterable(req.items())
                            .concatMap(line -> {
                                Map<String, Object> item = new HashMap<>();
                                item.put("item_code", line.itemCode());
                                item.put("qty", line.qty());
                                item.put("received_qty", line.qty());
                                item.put("warehouse", warehouse);
                                item.put("purchase_order", id);
                                if (notBlank(line.rowId())) item.put("purchase_order_item", line.rowId());
                                Double r = notBlank(line.rowId()) ? rateByRow.get(line.rowId()) : null;
                                if (r == null) r = rateByItem.get(line.itemCode());
                                final Double rate = r;
                                if (rate != null) {
                                    item.put("rate", rate);
                                    item.put("allow_zero_valuation_rate", 1);
                                }
                                if (!notBlank(line.batchNo())) {
                                    return Mono.just(item);
                                }
                                // Ensure the batch exists, then create a bundle and reference it on the line.
                                return ensureBatch(tenantId, line)
                                        .then(createSerialBatchBundle(tenantId, line, warehouse, rate))
                                        .map(bundleName -> {
                                            item.put("serial_and_batch_bundle", bundleName);
                                            return item;
                                        });
                            })
                            .collectList()
                            .flatMap(prItems -> {
                                Map<String, Object> body = new HashMap<>();
                                body.put("doctype", DOCTYPE_PURCHASE_RECEIPT);
                                body.put("supplier", po.supplier());
                                body.put("posting_date", postingDate);
                                body.put("set_warehouse", warehouse);
                                if (notBlank(req.supplierDeliveryNote())) {
                                    body.put("supplier_delivery_note", req.supplierDeliveryNote());
                                }
                                body.put("items", prItems);
                                // Submit on create so ERPNext posts stock and advances per_received.
                                body.put("docstatus", 1);
                                return router.create(tenantId, DOCTYPE_PURCHASE_RECEIPT, body, SINGLE_TYPE)
                                        .then(getPurchaseOrder(tenantId, id));
                            });
                });
    }

    private static boolean notBlank(String v) { return v != null && !v.isBlank(); }

    /** Creates the batch if missing (idempotent — a duplicate is fine). */
    private Mono<Void> ensureBatch(String tenantId, ReceiveStockRequest.Line line) {
        Map<String, Object> batch = new HashMap<>();
        batch.put("doctype", "Batch");
        batch.put("batch_id", line.batchNo().trim());
        batch.put("item", line.itemCode());
        if (notBlank(line.expiryDate())) batch.put("expiry_date", line.expiryDate().trim());
        return router.create(tenantId, "Batch", batch, SINGLE_TYPE)
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /** Creates a draft inward Serial & Batch Bundle for a batch line; returns its name. */
    private Mono<String> createSerialBatchBundle(String tenantId, ReceiveStockRequest.Line line,
                                                 String warehouse, Double rate) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("batch_no", line.batchNo().trim());
        entry.put("qty", line.qty());
        if (rate != null) entry.put("incoming_rate", rate);

        Map<String, Object> bundle = new HashMap<>();
        bundle.put("doctype", "Serial and Batch Bundle");
        bundle.put("item_code", line.itemCode());
        bundle.put("warehouse", warehouse);
        bundle.put("type_of_transaction", "Inward");
        bundle.put("voucher_type", DOCTYPE_PURCHASE_RECEIPT);
        bundle.put("entries", List.of(entry));

        return router.create(tenantId, "Serial and Batch Bundle", bundle, SINGLE_TYPE)
                .map(resp -> resp.data().name());
    }

    /** Purchase receipts posted against a PO, aggregated per receipt (via the linked child rows). */
    public Mono<List<ReceiptSummary>> listReceipts(String tenantId, String poId) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, "[\"parent\",\"qty\",\"creation\"]");
        params.put("filters", "[[\"purchase_order\",\"=\",\"" + poId.replace("\"", "") + "\"],"
                + "[\"parenttype\",\"=\",\"Purchase Receipt\"]]");
        params.put("order_by", "creation desc");
        params.put("limit_page_length", "500");
        return router.getList(tenantId, "Purchase Receipt Item", params, RAW_LIST_TYPE)
                .map(resp -> {
                    // Aggregate child rows by their parent receipt, preserving newest-first order.
                    java.util.LinkedHashMap<String, double[]> agg = new java.util.LinkedHashMap<>();
                    Map<String, String> created = new HashMap<>();
                    for (Map<String, Object> row : resp.data()) {
                        String parent = str(row.get("parent"));
                        if (parent.isEmpty()) continue;
                        agg.computeIfAbsent(parent, k -> new double[1])[0] += num(row.get("qty"));
                        created.putIfAbsent(parent, str(row.get("creation")));
                    }
                    return agg.entrySet().stream()
                            .map(e -> new ReceiptSummary(e.getKey(), created.get(e.getKey()), e.getValue()[0]))
                            .toList();
                });
    }

    private static final ParameterizedTypeReference<ErpNextListResponse<Map<String, Object>>> RAW_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    public Mono<PurchaseOrderDetail> submitPurchaseOrder(String tenantId, String id) {
        // ERPNext treats a docstatus 0 → 1 transition on the REST update as "submit".
        Map<String, Object> body = new HashMap<>();
        body.put("docstatus", 1);
        return router.replace(tenantId, DOCTYPE_PURCHASE_ORDER, id, body, SINGLE_TYPE)
                .map(resp -> toDetail(resp.data()));
    }

    @SuppressWarnings("unchecked")
    private PurchaseOrderDetail toDetail(ErpNextDoc doc) {
        List<PurchaseOrderDetail.Item> items = new ArrayList<>();
        if (doc.items() != null) {
            for (Map<String, Object> row : doc.items()) {
                items.add(new PurchaseOrderDetail.Item(
                        str(row.get("name")),
                        str(row.get("item_code")),
                        str(row.get("item_name")),
                        str(row.get("uom")),
                        num(row.get("qty")),
                        num(row.get("received_qty")),
                        num(row.get("rate")),
                        num(row.get("amount"))
                ));
            }
        }
        int docstatus = doc.docstatus() != null ? doc.docstatus() : 0;
        return new PurchaseOrderDetail(
                doc.name(), doc.name(), safeStr(doc.supplier()),
                safeStr(doc.transactionDate()), safeStr(doc.scheduleDate()),
                safeStr(doc.status()), docstatus, docstatus == 1,
                doc.remarks(), doc.grandTotal() != null ? doc.grandTotal() : 0,
                items);
    }

    private static String str(Object v) { return v != null ? v.toString() : ""; }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static String safeStr(String v) { return v != null ? v : ""; }

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
}
