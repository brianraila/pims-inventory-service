package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.web.model.RevenueReportSchemas;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class RevenueReportService {

    private final SalesInvoiceReportSupport support;

    public RevenueReportService(SalesInvoiceReportSupport support) {
        this.support = support;
    }

    public Mono<RevenueReportSchemas.RevenueReportResponse> getRevenueReport(
            String tenantId, Integer days, String from, String to, String status) {
        SalesInvoiceReportSupport.DateRange range =
                SalesInvoiceReportSupport.resolveDateRange(days, from, to, null);
        return support.fetchInvoices(tenantId, range.from(), range.to(), status, true)
                .map(invoices -> aggregate(invoices, range));
    }

    private RevenueReportSchemas.RevenueReportResponse aggregate(
            List<ErpNextDoc> invoices, SalesInvoiceReportSupport.DateRange range) {
        long rxCount = 0;
        long otcCount = 0;
        double rxRevenue = 0;
        double otcRevenue = 0;
        double totalRevenue = 0;

        for (ErpNextDoc invoice : invoices) {
            double amount = invoice.grandTotal() != null ? invoice.grandTotal() : 0;
            totalRevenue += amount;
            if (SalesInvoiceReportSupport.isPrescriptionSale(invoice)) {
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
                .orElse(SalesInvoiceReportSupport.CURRENCY);

        return new RevenueReportSchemas.RevenueReportResponse(
                new RevenueReportSchemas.RevenuePeriod(range.from(), range.to(), range.days()),
                totalRevenue,
                totalTransactions,
                avgOrderValue,
                new RevenueReportSchemas.TransactionBucket(rxCount, rxRevenue),
                new RevenueReportSchemas.TransactionBucket(otcCount, otcRevenue),
                currency);
    }
}
