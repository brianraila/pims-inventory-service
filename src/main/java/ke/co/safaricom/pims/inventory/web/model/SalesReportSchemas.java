package ke.co.safaricom.pims.inventory.web.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.UUID;

public final class SalesReportSchemas {

    private SalesReportSchemas() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SalesRow(
            String period,
            UUID productId,
            String productName,
            String genericName,
            String category,
            long unitsSold,
            double revenue,
            String currency) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SalesSummary(double totalRevenue, long totalUnits, String currency) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SalesReportResponse(
            List<SalesRow> data,
            InventoryApiSchemas.Pagination pagination,
            SalesSummary summary) {}
}
