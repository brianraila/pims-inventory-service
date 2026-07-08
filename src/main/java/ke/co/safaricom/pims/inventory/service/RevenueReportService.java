package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.model.RevenueReportSchemas;
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
public class RevenueReportService {

    private static final String SI_DOCTYPE = "Sales Invoice";
    private static final String CURRENCY = "KES";
    private static final int FETCH_PAGE_SIZE = 500;

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> INVOICE_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final ErpNextTenantRouter router;

    public RevenueReportService(ErpNextTenantRouter router) {
        this.router = router;
    }

    public Mono<RevenueReportSchemas.RevenueReportResponse> getRevenueReport(
            String tenantId, Integer days, String from, String to, String status) {
        DateRange range = resolveDateRange(days, from, to);
        return fetchInvoices(tenantId, range.from(), range.to(), status)
                .map(invoices -> aggregate(invoices, range));
    }

    private RevenueReportSchemas.RevenueReportResponse aggregate(
            List<ErpNextDoc> invoices, DateRange range) {
        long rxCount = 0;
        long otcCount = 0;
        double rxRevenue = 0;
        double otcRevenue = 0;
        double totalRevenue = 0;

        for (ErpNextDoc invoice : invoices) {
            double amount = invoice.grandTotal() != null ? invoice.grandTotal() : 0;
            totalRevenue += amount;
            if (isPrescriptionSale(invoice)) {
                rxCount++;
                rxRevenue += amount;
            } else {
                otcCount++;
                otcRevenue += amount;
            }
        }

        long totalTransactions = invoices.size();
        double avgOrderValue = totalTransactions > 0 ? totalRevenue / totalTransactions : 0;
        String currency = invoices.stream()
                .map(ErpNextDoc::currency)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(CURRENCY);

        return new RevenueReportSchemas.RevenueReportResponse(
                new RevenueReportSchemas.RevenuePeriod(range.from(), range.to(), range.days()),
                totalRevenue,
                totalTransactions,
                avgOrderValue,
                new RevenueReportSchemas.TransactionBucket(rxCount, rxRevenue),
                new RevenueReportSchemas.TransactionBucket(otcCount, otcRevenue),
                currency);
    }

    private static boolean isPrescriptionSale(ErpNextDoc invoice) {
        if (StringUtils.hasText(invoice.customPimsPrescriptionId())) {
            return true;
        }
        if (StringUtils.hasText(invoice.customPimsSaleType())) {
            Enums.SaleType type = Enums.SaleType.fromJson(invoice.customPimsSaleType());
            return type == Enums.SaleType.prescription || type == Enums.SaleType.mixed;
        }
        return false;
    }

    private Mono<List<ErpNextDoc>> fetchInvoices(String tenantId, String from, String to, String status) {
        Map<String, String> params = new HashMap<>();
        params.put("fields",
                "[\"name\",\"posting_date\",\"currency\",\"docstatus\",\"status\",\"grand_total\",\"custom_pims_prescription_id\",\"custom_pims_sale_type\"]");
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

    private static DateRange resolveDateRange(Integer days, String from, String to) {
        if (StringUtils.hasText(from) || StringUtils.hasText(to)) {
            String resolvedFrom = StringUtils.hasText(from) ? from.trim() : LocalDate.now().minusDays(30).toString();
            String resolvedTo = StringUtils.hasText(to) ? to.trim() : LocalDate.now().toString();
            return new DateRange(resolvedFrom, resolvedTo, null);
        }
        int window = days != null && days > 0 ? days : 30;
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(window);
        return new DateRange(start.toString(), end.toString(), window);
    }

    private record DateRange(String from, String to, Integer days) {}
}
