package ke.co.safaricom.pims.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Stock adjustment (Material Receipt or Material Issue)")
public record StockAdjustmentResponse(
        @Schema(description = "ERPNext Stock Entry name") String id,
        @Schema(description = "Stock Entry document name (adjustment number)") String adjustmentNumber,
        @Schema(description = "Item code") String product,
        @Schema(description = "Type: addition | reduction") String type,
        @Schema(description = "Adjusted quantity") double quantity,
        @Schema(description = "Reason for adjustment") String reason,
        @Schema(description = "Transaction date (YYYY-MM-DD)") String date,
        @Schema(description = "User who created the adjustment") String user
) {}
