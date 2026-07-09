package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Shared ERPNext Sales Invoice / Payment Entry fetch helpers used by revenue and sales report services.
 */
@Component
public class SalesInvoiceReportSupport {

    private static final Logger log = LoggerFactory.getLogger(SalesInvoiceReportSupport.class);

    public static final String CURRENCY = "KES";
    public static final int FETCH_PAGE_SIZE = 500;

    private static final String SI_DOCTYPE = "Sales Invoice";
    private static final String SII_DOCTYPE = "Sales Invoice Item";
    private static final String PE_DOCTYPE = "Payment Entry";
    private static final String LIST_SALES_INVOICES_METHOD = "pims.api.reports.list_sales_invoices";
    private static final String LIST_SALES_INVOICE_ITEMS_METHOD = "pims.api.reports.list_sales_invoice_items";

    private static final String ANALYTICS_INVOICE_FIELDS =
            "[\"name\",\"posting_date\",\"currency\",\"docstatus\",\"status\",\"grand_total\","
                    + "\"net_total\",\"total_taxes_and_charges\"]";

    private static final String PIMS_ANALYTICS_INVOICE_FIELDS =
            "[\"name\",\"posting_date\",\"currency\",\"docstatus\",\"status\",\"grand_total\","
                    + "\"net_total\",\"total_taxes_and_charges\",\"pims_prescription_id\","
                    + "\"pims_sale_type\",\"pims_order_status\"]";

    private static final String MINIMAL_INVOICE_FIELDS =
            "[\"name\",\"posting_date\",\"currency\",\"docstatus\",\"status\"]";

    private static final String PIMS_MINIMAL_INVOICE_FIELDS =
            "[\"name\",\"posting_date\",\"currency\",\"docstatus\",\"status\","
                    + "\"pims_prescription_id\",\"pims_sale_type\",\"pims_order_status\"]";

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> DOC_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> DOC_SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextListResponse<Map<String, Object>>> MAP_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextMessageResponse<List<ErpNextDoc>>> REPORT_API_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextMessageResponse<List<Map<String, Object>>>> ITEM_REPORT_API_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final DateTimeFormatter DD_MM_YY = DateTimeFormatter.ofPattern("dd-MM-yy");
    private static final DateTimeFormatter DD_MM_YYYY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final ErpNextTenantRouter router;

    public SalesInvoiceReportSupport(ErpNextTenantRouter router) {
        this.router = router;
    }

    public Mono<List<ErpNextDoc>> fetchInvoices(
            String tenantId, String from, String to, String status, boolean analyticsFields) {
        Map<String, String> params = new HashMap<>();
        params.put("fields", analyticsFields ? ANALYTICS_INVOICE_FIELDS : MINIMAL_INVOICE_FIELDS);
        params.put("order_by", "posting_date desc");
        params.put("limit_page_length", String.valueOf(FETCH_PAGE_SIZE));
        params.put("filters", buildInvoiceFilters(from, to, status));
        return fetchDocPage(tenantId, SI_DOCTYPE, params, 0, new ArrayList<>());
    }

    /**
     * Loads invoices with Rx/OTC metadata via the PIMS report API when available,
     * otherwise falls back to native {@code pims_*} list fields, then N+1 getOne.
     */
    public Mono<List<ErpNextDoc>> fetchInvoicesWithSaleMetadata(
            String tenantId, String from, String to, String status, boolean analyticsFields) {
        return fetchInvoicesViaReportApi(tenantId, from, to, status, analyticsFields)
                .onErrorResume(ex -> {
                    log.debug("PIMS report API unavailable for tenant {}: {}", tenantId, ex.toString());
                    return fetchInvoicesViaPimsListFields(tenantId, from, to, status, analyticsFields)
                            .onErrorResume(listEx -> {
                                log.debug("PIMS list-field fallback failed for tenant {}: {}",
                                        tenantId, listEx.toString());
                                return fetchInvoicesWithGetOneFallback(
                                        tenantId, from, to, status, analyticsFields);
                            });
                });
    }

    private Mono<List<ErpNextDoc>> fetchInvoicesViaReportApi(
            String tenantId, String from, String to, String status, boolean analyticsFields) {
        return fetchReportApiPage(tenantId, from, to, status, analyticsFields, 0, new ArrayList<>());
    }

    private Mono<List<ErpNextDoc>> fetchReportApiPage(
            String tenantId,
            String from,
            String to,
            String status,
            boolean analyticsFields,
            int offset,
            List<ErpNextDoc> accumulated) {
        Map<String, Object> body = reportApiBody(from, to, status, analyticsFields, offset);
        return router.callMethod(tenantId, LIST_SALES_INVOICES_METHOD, body, REPORT_API_TYPE)
                .map(resp -> resp.message() != null ? resp.message() : List.<ErpNextDoc>of())
                .flatMap(page -> {
                    accumulated.addAll(page);
                    if (page.size() < FETCH_PAGE_SIZE) {
                        return Mono.just(accumulated);
                    }
                    return fetchReportApiPage(
                            tenantId, from, to, status, analyticsFields, offset + page.size(), accumulated);
                });
    }

    private Mono<List<ErpNextDoc>> fetchInvoicesViaPimsListFields(
            String tenantId, String from, String to, String status, boolean analyticsFields) {
        Map<String, String> params = new HashMap<>();
        params.put("fields", analyticsFields ? PIMS_ANALYTICS_INVOICE_FIELDS : PIMS_MINIMAL_INVOICE_FIELDS);
        params.put("order_by", "posting_date desc");
        params.put("limit_page_length", String.valueOf(FETCH_PAGE_SIZE));
        params.put("filters", buildInvoiceFilters(from, to, status));
        return fetchDocPage(tenantId, SI_DOCTYPE, params, 0, new ArrayList<>());
    }

    private Mono<List<ErpNextDoc>> fetchInvoicesWithGetOneFallback(
            String tenantId, String from, String to, String status, boolean analyticsFields) {
        return fetchInvoices(tenantId, from, to, status, analyticsFields)
                .flatMap(invoices -> {
                    if (invoices.isEmpty()) {
                        return Mono.just(invoices);
                    }
                    return Flux.fromIterable(invoices)
                            .flatMap(inv -> router.getOne(tenantId, SI_DOCTYPE, inv.name(), DOC_SINGLE_TYPE)
                                    .map(ErpNextSingleResponse::data), 8)
                            .collectList();
                });
    }

    private static Map<String, Object> reportApiBody(
            String from, String to, String status, boolean analyticsFields, int offset) {
        Map<String, Object> body = new HashMap<>();
        if (StringUtils.hasText(from)) {
            body.put("from_date", from.trim());
        }
        if (StringUtils.hasText(to)) {
            body.put("to_date", to.trim());
        }
        if (StringUtils.hasText(status)) {
            body.put("status", status.trim());
        }
        body.put("analytics_fields", analyticsFields ? 1 : 0);
        body.put("limit_start", offset);
        body.put("limit_page_length", FETCH_PAGE_SIZE);
        return body;
    }

    public Mono<List<Map<String, Object>>> fetchInvoiceItems(String tenantId, List<String> parents) {
        if (parents == null || parents.isEmpty()) {
            return Mono.just(List.of());
        }
        return fetchInvoiceItemsViaList(tenantId, parents)
                .flatMap(rows -> {
                    if (rowsHaveItemData(rows)) {
                        return Mono.just(rows);
                    }
                    return fetchInvoiceItemsViaReportApi(tenantId, parents)
                            .onErrorResume(ex -> {
                                log.debug("PIMS invoice-item report API unavailable for tenant {}: {}",
                                        tenantId, ex.toString());
                                return fetchInvoiceItemsViaGetOne(tenantId, parents);
                            });
                });
    }

    /**
     * Fetches submitted Payment Entries that reference any of the given Sales Invoice names.
     * When the invoice set is large, names are queried in chunks to keep filter URLs manageable.
     */
    public Mono<List<ErpNextDoc>> fetchPaymentEntriesForInvoices(String tenantId, List<String> invoiceNames) {
        if (invoiceNames == null || invoiceNames.isEmpty()) {
            return Mono.just(List.of());
        }
        List<List<String>> chunks = chunk(invoiceNames, 50);
        return fetchPaymentChunks(tenantId, chunks, 0, new ArrayList<>());
    }

    public static boolean isPrescriptionSale(ErpNextDoc invoice) {
        if (StringUtils.hasText(invoice.customPimsPrescriptionId())) {
            return true;
        }
        if (StringUtils.hasText(invoice.customPimsSaleType())) {
            Enums.SaleType type = Enums.SaleType.fromJson(invoice.customPimsSaleType());
            return type == Enums.SaleType.prescription || type == Enums.SaleType.mixed;
        }
        return false;
    }

    /**
     * Resolves a reporting window from ISO from/to, rolling days, or a design-style {@code date}
     * alias ({@code DD-MM-YYYY} single or comma-separated range; also accepts ISO dates).
     */
    public static DateRange resolveDateRange(Integer days, String from, String to, String date) {
        if (StringUtils.hasText(date)) {
            DateRange fromAlias = parseDateAlias(date.trim());
            if (fromAlias != null) {
                return fromAlias;
            }
        }
        if (StringUtils.hasText(from) || StringUtils.hasText(to)) {
            String resolvedFrom = StringUtils.hasText(from)
                    ? normalizeToIso(from.trim())
                    : LocalDate.now().minusDays(30).toString();
            String resolvedTo = StringUtils.hasText(to)
                    ? normalizeToIso(to.trim())
                    : LocalDate.now().toString();
            return new DateRange(resolvedFrom, resolvedTo, null);
        }
        int window = days != null && days > 0 ? days : 30;
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(window);
        return new DateRange(start.toString(), end.toString(), window);
    }

    /** Previous window of equal length ending the day before {@code range.from()}. */
    public static DateRange previousPeriod(DateRange range) {
        LocalDate from = LocalDate.parse(range.from());
        LocalDate to = LocalDate.parse(range.to());
        long daysInclusive = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(daysInclusive - 1);
        return new DateRange(prevFrom.toString(), prevTo.toString(), null);
    }

    public static TrendResult computeTrend(double current, double previous) {
        if (previous == 0.0) {
            if (current == 0.0) {
                return new TrendResult(0.0, "flat");
            }
            return new TrendResult(100.0, "up");
        }
        double changePct = ((current - previous) / Math.abs(previous)) * 100.0;
        changePct = Math.round(changePct * 10.0) / 10.0;
        String trend;
        if (changePct > 0.05) {
            trend = "up";
        } else if (changePct < -0.05) {
            trend = "down";
        } else {
            trend = "flat";
        }
        return new TrendResult(changePct, trend);
    }

    public static String toPeriod(String postingDate) {
        if (!StringUtils.hasText(postingDate)) return null;
        try {
            LocalDate date = LocalDate.parse(postingDate.trim());
            return YearMonth.from(date).toString();
        } catch (DateTimeParseException ex) {
            String trimmed = postingDate.trim();
            return trimmed.length() >= 7 ? trimmed.substring(0, 7) : null;
        }
    }

    public static double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    public static long calcTotalPages(long total, int limit) {
        if (limit <= 0) return 0;
        return (long) Math.ceil((double) total / (double) limit);
    }

    public static String normalizePaymentMethod(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "unknown";
        }
        String n = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        if (n.contains("mpesa") || n.equals("m-pesa") || n.contains("mpessa")) {
            return "mpesa";
        }
        if (n.contains("cash")) {
            return "cash";
        }
        if (n.contains("card") || n.contains("visa") || n.contains("mastercard")) {
            return "card";
        }
        if (n.contains("bank") || n.contains("transfer") || n.contains("eft")) {
            return "bank_transfer";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    // ---- private helpers ------------------------------------------------------

    private Mono<List<ErpNextDoc>> fetchDocPage(
            String tenantId,
            String doctype,
            Map<String, String> params,
            int offset,
            List<ErpNextDoc> accumulated) {
        Map<String, String> pageParams = new HashMap<>(params);
        pageParams.put("limit_start", String.valueOf(offset));
        return router.getList(tenantId, doctype, pageParams, DOC_LIST_TYPE)
                .flatMap(resp -> {
                    accumulated.addAll(resp.data());
                    if (resp.data().size() < FETCH_PAGE_SIZE) {
                        return Mono.just(accumulated);
                    }
                    return fetchDocPage(tenantId, doctype, params, offset + resp.data().size(), accumulated);
                });
    }

    private Mono<List<Map<String, Object>>> fetchInvoiceItemsViaReportApi(
            String tenantId, List<String> parents) {
        return fetchInvoiceItemReportPage(tenantId, parents, 0, new ArrayList<>());
    }

    private Mono<List<Map<String, Object>>> fetchInvoiceItemReportPage(
            String tenantId,
            List<String> parents,
            int offset,
            List<Map<String, Object>> accumulated) {
        Map<String, Object> body = new HashMap<>();
        body.put("parents", parents);
        body.put("limit_start", offset);
        body.put("limit_page_length", FETCH_PAGE_SIZE);
        return router.callMethod(tenantId, LIST_SALES_INVOICE_ITEMS_METHOD, body, ITEM_REPORT_API_TYPE)
                .map(resp -> resp.message() != null ? resp.message() : List.<Map<String, Object>>of())
                .flatMap(page -> {
                    accumulated.addAll(page);
                    if (page.size() < FETCH_PAGE_SIZE) {
                        return Mono.just(accumulated);
                    }
                    return fetchInvoiceItemReportPage(
                            tenantId, parents, offset + page.size(), accumulated);
                });
    }

    private Mono<List<Map<String, Object>>> fetchInvoiceItemsViaList(
            String tenantId, List<String> parents) {
        return fetchInvoiceItemPage(tenantId, parents, 0, new ArrayList<>());
    }

    private Mono<List<Map<String, Object>>> fetchInvoiceItemsViaGetOne(
            String tenantId, List<String> parents) {
        return Flux.fromIterable(parents)
                .flatMap(name -> router.getOne(tenantId, SI_DOCTYPE, name, DOC_SINGLE_TYPE)
                        .map(ErpNextSingleResponse::data), 8)
                .flatMapIterable(inv -> {
                    List<Map<String, Object>> lines = inv.items() != null ? inv.items() : List.of();
                    List<Map<String, Object>> withParent = new ArrayList<>(lines.size());
                    for (Map<String, Object> line : lines) {
                        Map<String, Object> row = new HashMap<>(line);
                        row.putIfAbsent("parent", inv.name());
                        withParent.add(row);
                    }
                    return withParent;
                })
                .collectList();
    }

    private static boolean rowsHaveItemData(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return false;
        }
        for (Map<String, Object> row : rows) {
            if (StringUtils.hasText(Objects.toString(row.get("item_code"), null))) {
                return true;
            }
        }
        return false;
    }

    private Mono<List<Map<String, Object>>> fetchInvoiceItemPage(
            String tenantId, List<String> parents, int offset, List<Map<String, Object>> accumulated) {
        Map<String, String> params = new HashMap<>();
        params.put("fields", "[\"item_code\",\"item_name\",\"qty\",\"amount\",\"parent\",\"rate\"]");
        params.put("filters", buildInFilter("parent", parents));
        params.put("limit_page_length", String.valueOf(FETCH_PAGE_SIZE));
        params.put("limit_start", String.valueOf(offset));
        return router.getList(tenantId, SII_DOCTYPE, params, MAP_LIST_TYPE)
                .flatMap(resp -> {
                    accumulated.addAll(resp.data());
                    if (resp.data().size() < FETCH_PAGE_SIZE) {
                        return Mono.just(accumulated);
                    }
                    return fetchInvoiceItemPage(tenantId, parents, offset + resp.data().size(), accumulated);
                });
    }

    private Mono<List<ErpNextDoc>> fetchPaymentChunks(
            String tenantId, List<List<String>> chunks, int index, List<ErpNextDoc> accumulated) {
        if (index >= chunks.size()) {
            return Mono.just(accumulated);
        }
        Map<String, String> params = new HashMap<>();
        params.put("fields",
                "[\"name\",\"mode_of_payment\",\"paid_amount\",\"posting_date\",\"docstatus\"]");
        params.put("filters", "[[\"Payment Entry Reference\",\"reference_name\",\"in\","
                + jsonStringArray(chunks.get(index)) + "],[\"docstatus\",\"=\",1]]");
        params.put("limit_page_length", String.valueOf(FETCH_PAGE_SIZE));
        return fetchDocPage(tenantId, PE_DOCTYPE, params, 0, new ArrayList<>())
                .flatMap(page -> {
                    accumulated.addAll(page);
                    return fetchPaymentChunks(tenantId, chunks, index + 1, accumulated);
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

    private static String buildInFilter(String field, List<String> values) {
        return "[[\"" + field + "\",\"in\"," + jsonStringArray(values) + "]]";
    }

    private static String jsonStringArray(List<String> values) {
        String joined = values.stream()
                .map(name -> "\"" + name.replace("\"", "\\\"") + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return "[" + joined + "]";
    }

    private static <T> List<List<T>> chunk(List<T> source, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < source.size(); i += size) {
            out.add(source.subList(i, Math.min(i + size, source.size())));
        }
        return out;
    }

    private static DateRange parseDateAlias(String raw) {
        String[] parts = raw.split(",");
        if (parts.length == 1) {
            LocalDate single = parseFlexibleDate(parts[0].trim());
            if (single == null) return null;
            return new DateRange(single.toString(), single.toString(), null);
        }
        if (parts.length >= 2) {
            LocalDate start = parseFlexibleDate(parts[0].trim());
            LocalDate end = parseFlexibleDate(parts[1].trim());
            if (start == null || end == null) return null;
            if (end.isBefore(start)) {
                LocalDate tmp = start;
                start = end;
                end = tmp;
            }
            return new DateRange(start.toString(), end.toString(), null);
        }
        return null;
    }

    private static String normalizeToIso(String raw) {
        LocalDate parsed = parseFlexibleDate(raw);
        return parsed != null ? parsed.toString() : raw;
    }

    private static LocalDate parseFlexibleDate(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String value = raw.trim();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            // try DD-MM-YYYY / DD-MM-YY next
        }
        try {
            return LocalDate.parse(value, DD_MM_YYYY);
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return LocalDate.parse(value, DD_MM_YY);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public record DateRange(String from, String to, Integer days) {}

    public record TrendResult(double changePct, String trend) {}
}
