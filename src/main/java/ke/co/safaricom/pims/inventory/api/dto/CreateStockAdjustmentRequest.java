package ke.co.safaricom.pims.inventory.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "Request to create a stock adjustment entry")
public record CreateStockAdjustmentRequest(
        @NotBlank @Schema(description = "Item code") String itemCode,
        @NotBlank @Schema(description = "Warehouse / location") String warehouse,
        @NotNull @Positive @Schema(description = "Quantity to adjust") Double quantity,
        @NotBlank @Schema(description = "Type: addition | reduction") String type,
        @Schema(description = "Reason / remarks") String reason,
        @Schema(description = "Batch number (if applicable)") String batchNo
) {}
