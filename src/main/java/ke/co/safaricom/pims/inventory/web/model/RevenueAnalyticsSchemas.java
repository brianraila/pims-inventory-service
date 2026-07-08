package ke.co.safaricom.pims.inventory.web.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.UUID;

public final class RevenueAnalyticsSchemas {

    private RevenueAnalyticsSchemas() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MoneyMetricCard(
            double value,
            String currency,
            double changePct,
            String trend) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PercentMetricCard(
            double value,
            String unit,
            double changePct,
            String trend) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevenueAnalyticsResponse(
            MoneyMetricCard totalRevenue,
            MoneyMetricCard grossProfit,
            MoneyMetricCard netProfit,
            PercentMetricCard grossProfitMargin) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrendSeriesPoint(
            String date,
            double revenue,
            double profit,
            long units) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevenueTrendsResponse(String metric, List<TrendSeriesPoint> series) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CategoryRevenue(String category, double revenue) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevenueByCategoryResponse(
            double total,
            String currency,
            List<CategoryRevenue> categories) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TopProduct(
            int rank,
            UUID productId,
            String name,
            long unitsSold,
            double revenue,
            String currency,
            String trend) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TopSellingProductsResponse(List<TopProduct> products) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentMethodBreakdown(
            String method,
            double value,
            double pct,
            double changePct,
            String trend) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevenueByPaymentMethodResponse(
            double total,
            String currency,
            List<PaymentMethodBreakdown> methods) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransactionTypeMonth(String month, long otc, long prescription) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransactionTypesResponse(List<TransactionTypeMonth> data) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevenueOverviewRow(
            String period,
            UUID productId,
            String productName,
            String productSubtitle,
            String category,
            double revenue,
            String currency,
            long unitsSold,
            double avgPrice) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevenueOverviewResponse(
            int page,
            int pageSize,
            long totalPages,
            long totalRecords,
            List<RevenueOverviewRow> data) {}
}
