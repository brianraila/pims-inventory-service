package ke.co.safaricom.pims.inventory.web.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

public final class StockReportSchemas {

    private StockReportSchemas() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StockReportRow(
            String productName,
            String batchNumber,
            double availableStock,
            Enums.UnitOfMeasure unitOfMeasure,
            Double unitPrice,
            double stockValue,
            String expiryDate,
            String status,
            String outOfStockDate,
            double reorderLevel,
            long daysOutOfStock) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StockReportResponse(
            List<StockReportRow> data,
            InventoryApiSchemas.Pagination pagination) {}
}
