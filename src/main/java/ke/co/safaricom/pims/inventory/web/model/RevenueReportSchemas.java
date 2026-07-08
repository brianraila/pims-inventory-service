package ke.co.safaricom.pims.inventory.web.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class RevenueReportSchemas {

    private RevenueReportSchemas() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RevenuePeriod(String from, String to, Integer days) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TransactionBucket(long count, double revenue) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevenueReportResponse(
            RevenuePeriod period,
            double totalRevenue,
            long totalTransactions,
            double avgOrderValue,
            TransactionBucket prescriptionTransactions,
            TransactionBucket otcTransactions,
            String currency) {}
}
