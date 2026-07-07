package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.model.SalesReportSchemas;
import ke.co.safaricom.pims.inventory.web.util.ItemExtrasCodec;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SalesReportService {

    private static final String SI_DOCTYPE = "Sales Invoice";
    private static final String SII_DOCTYPE = "Sales Invoice Item";
    private static final String CURRENCY = "KES";
    private static final int FETCH_PAGE_SIZE = 500;

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> INVOICE_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextListResponse<Map<String, Object>>> ITEM_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final ErpNextTenantRouter router;
    private final InventoryService inventoryService;

    public SalesReportService(ErpNextTenantRouter router, InventoryService inventoryService) {
        this.router = router;
        this.inventoryService = inventoryService;
    }

    public Mono<SalesReportSchemas.SalesReportResponse> getSalesReport(
            String tenantId, String from, String to, String status, int page, int limit) {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(limit, 100));

        return fetchInvoices(tenantId, from, to, status)
                .flatMap(invoices -> {
                    if (invoices.isEmpty()) {
                        return Mono.just(emptyResponse(safePage, safeLimit));
                    }
                    Map<String, InvoiceMeta> invoiceMeta = new HashMap<>();
                    for (ErpNextDoc inv : invoices) {
                        invoiceMeta.put(inv.name(), new InvoiceMeta(
                                inv.postingDate(),
                                inv.currency() != null ? inv.currency() : CURRENCY));
                    }
                    return fetchAllInvoiceItems(tenantId, new ArrayList<>(invoiceMeta.keySet()))
                            .zipWith(inventoryService.listItems(tenantId))
                            .map(tuple -> buildResponse(
                                    tenantId,
                                    tuple.getT1(),
                                    invoiceMeta,
                                    tuple.getT2(),
                                    safePage,
                                    safeLimit));
                });
    }

    private Mono<List<ErpNextDoc>> fetchInvoices(
            String tenantId, String from, String to, String status) {
        Map<String, String> params = new HashMap<>();
        params.put("fields", "[\"name\",\"posting_date\",\"currency\",\"docstatus\",\"status\"]");
        params.put("order_by", "posting_date desc");
        params.put("limit_page_length", String.valueOf(FETCH_PAGE_SIZE));
        params.put("filters", buildInvoiceFilters(from, to, status));
        return fetchInvoicePage(tenantId, params, 0, new ArrayList<>());
    }

    private Mono<List<ErpNextDoc>> fetchInvoicePage(
            String tenantId, Map<String, String> params, int offset, List<ErpNextDoc> accumulated) {
        Map<String, String> pageParams = new HashMap<>(params);
        pageParams.put("limit_start", String.valueOf(offset));
        return router.getList(tenantId, SI_DOCTYPE, pageParams, INVOICE_LIST_TYPE)
                .flatMap(resp -> {
                    accumulated.addAll(resp.data());
                    if (resp.data().size() < FETCH_PAGE_SIZE) {
                        return Mono.just(accumulated);
                    }
                    return fetchInvoicePage(tenantId, params, offset + resp.data().size(), accumulated);
                });
    }

    private static String buildInvoiceFilters(String from, String to, String status) {
        List<String> filters = new ArrayList<>();
        filters.add("[\"docstatus\",\"!=\",2]");
        applyStatusFilter(filters, status);
        if (StringUtils.hasText(from)) {
            filters.add("[\"posting_date\",\">=\",\"" + from.trim() + "\"]");
        }
        if (StringUtils.hasText(to)) {
            filters.add("[\"posting_date\",\"<=\",\"" + to.trim() + "\"]");
        }
        return "[" + String.join(",", filters) + "]";
    }

    private static void applyStatusFilter(List<String> filters, String status) {
        if (!StringUtils.hasText(status)) {
            filters.add("[\"docstatus\",\"=\",1]");
            return;
        }
        String normalized = status.trim().toLowerCase();
        switch (normalized) {
            case "submitted" -> filters.add("[\"docstatus\",\"=\",1]");
            case "draft" -> filters.add("[\"docstatus\",\"=\",0]");
            case "cancelled" -> filters.add("[\"docstatus\",\"=\",2]");
            default -> {
                filters.add("[\"docstatus\",\"=\",1]");
                filters.add("[\"status\",\"=\",\"" + status.trim() + "\"]");
            }
        }
    }

    private Mono<List<Map<String, Object>>> fetchAllInvoiceItems(String tenantId, List<String> parents) {
        if (parents.isEmpty()) {
            return Mono.just(List.of());
        }
        return fetchInvoiceItemPage(tenantId, parents, 0, new ArrayList<>());
    }

    private Mono<List<Map<String, Object>>> fetchInvoiceItemPage(
            String tenantId, List<String> parents, int offset, List<Map<String, Object>> accumulated) {
        Map<String, String> params = new HashMap<>();
        params.put("fields", "[\"item_code\",\"item_name\",\"qty\",\"amount\",\"parent\"]");
        params.put("filters", buildInvoiceItemFilters(parents));
        params.put("limit_page_length", String.valueOf(FETCH_PAGE_SIZE));
        params.put("limit_start", String.valueOf(offset));
        return router.getList(tenantId, SII_DOCTYPE, params, ITEM_LIST_TYPE)
                .flatMap(resp -> {
                    accumulated.addAll(resp.data());
                    if (resp.data().size() < FETCH_PAGE_SIZE) {
                        return Mono.just(accumulated);
                    }
                    return fetchInvoiceItemPage(tenantId, parents, offset + resp.data().size(), accumulated);
                });
    }

    private static String buildInvoiceItemFilters(List<String> parents) {
        String joined = parents.stream()
                .map(name -> "\"" + name.replace("\"", "\\\"") + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return "[[\"parent\",\"in\",[" + joined + "]]]";
    }

    private SalesReportSchemas.SalesReportResponse buildResponse(
            String tenantId,
            List<Map<String, Object>> itemRows,
            Map<String, InvoiceMeta> invoiceMeta,
            List<InventoryItemResponse> items,
            int page,
            int limit) {
        Map<String, InventoryItemResponse> itemsByCode = new HashMap<>();
        for (InventoryItemResponse item : items) {
            itemsByCode.put(item.id(), item);
        }

        Map<AggregateKey, AggregateValue> aggregates = new HashMap<>();
        for (Map<String, Object> row : itemRows) {
            String parent = Objects.toString(row.get("parent"), null);
            String itemCode = Objects.toString(row.get("item_code"), null);
            if (!StringUtils.hasText(parent) || !StringUtils.hasText(itemCode)) continue;

            InvoiceMeta meta = invoiceMeta.get(parent);
            if (meta == null || !StringUtils.hasText(meta.postingDate())) continue;

            String period = toPeriod(meta.postingDate());
            if (period == null) continue;

            double qty = toDouble(row.get("qty"));
            double amount = toDouble(row.get("amount"));
            String currency = meta.currency() != null ? meta.currency() : CURRENCY;

            AggregateKey key = new AggregateKey(period, itemCode);
            aggregates.computeIfAbsent(key, ignored -> new AggregateValue(currency))
                    .add(qty, amount);
        }

        List<SalesReportSchemas.SalesRow> rows = new ArrayList<>();
        for (Map.Entry<AggregateKey, AggregateValue> entry : aggregates.entrySet()) {
            AggregateKey key = entry.getKey();
            AggregateValue value = entry.getValue();
            InventoryItemResponse item = itemsByCode.get(key.itemCode());
            Map<String, Object> extras = item != null && item.pimsCustomColumns() != null
                    ? item.pimsCustomColumns()
                    : Map.of();

            String productName = item != null ? item.name() : key.itemCode();
            String genericName = "";
            if (item != null) {
                String fromExtras = ItemExtrasCodec.displayGenericName(item.genericName(), extras);
                genericName = StringUtils.hasText(fromExtras)
                        ? fromExtras
                        : (StringUtils.hasText(item.genericName()) ? item.genericName() : "");
            }
            String category = item != null
                    ? (extras.containsKey("category")
                            ? Objects.toString(extras.get("category"), item.category())
                            : item.category())
                    : "";

            rows.add(new SalesReportSchemas.SalesRow(
                    key.period(),
                    StableEntityIds.itemId(tenantId, key.itemCode()),
                    productName,
                    genericName,
                    category,
                    Math.round(value.units()),
                    value.revenue(),
                    value.currency()));
        }

        rows.sort(Comparator
                .comparing(SalesReportSchemas.SalesRow::period).reversed()
                .thenComparing(SalesReportSchemas.SalesRow::productName, String.CASE_INSENSITIVE_ORDER));

        double totalRevenue = rows.stream().mapToDouble(SalesReportSchemas.SalesRow::revenue).sum();
        long totalUnits = rows.stream().mapToLong(SalesReportSchemas.SalesRow::unitsSold).sum();
        String summaryCurrency = rows.isEmpty() ? CURRENCY : rows.get(0).currency();

        long total = rows.size();
        int from = Math.max(0, (page - 1) * limit);
        int to = Math.min(rows.size(), from + limit);
        List<SalesReportSchemas.SalesRow> pageRows = from >= rows.size() ? List.of() : rows.subList(from, to);

        InventoryApiSchemas.Pagination pagination = new InventoryApiSchemas.Pagination(
                page, limit, total, calcTotalPages(total, limit));
        SalesReportSchemas.SalesSummary summary =
                new SalesReportSchemas.SalesSummary(totalRevenue, totalUnits, summaryCurrency);

        return new SalesReportSchemas.SalesReportResponse(pageRows, pagination, summary);
    }

    private static SalesReportSchemas.SalesReportResponse emptyResponse(int page, int limit) {
        InventoryApiSchemas.Pagination pagination =
                new InventoryApiSchemas.Pagination(page, limit, 0, 0);
        SalesReportSchemas.SalesSummary summary =
                new SalesReportSchemas.SalesSummary(0.0, 0L, CURRENCY);
        return new SalesReportSchemas.SalesReportResponse(List.of(), pagination, summary);
    }

    private static String toPeriod(String postingDate) {
        if (!StringUtils.hasText(postingDate)) return null;
        try {
            LocalDate date = LocalDate.parse(postingDate.trim());
            return YearMonth.from(date).toString();
        } catch (DateTimeParseException ex) {
            String trimmed = postingDate.trim();
            return trimmed.length() >= 7 ? trimmed.substring(0, 7) : null;
        }
    }

    private static double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private static long calcTotalPages(long total, int limit) {
        if (limit <= 0) return 0;
        return (long) Math.ceil((double) total / (double) limit);
    }

    private record InvoiceMeta(String postingDate, String currency) {}

    private record AggregateKey(String period, String itemCode) {}

    private static final class AggregateValue {
        private double units;
        private double revenue;
        private final String currency;

        private AggregateValue(String currency) {
            this.currency = currency;
        }

        private void add(double qty, double amount) {
            units += qty;
            revenue += amount;
        }

        private double units() {
            return units;
        }

        private double revenue() {
            return revenue;
        }

        private String currency() {
            return currency;
        }
    }
}
