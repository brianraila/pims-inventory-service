package ke.co.safaricom.pims.inventory.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

@Schema(description = "Request to create a purchase order in ERPNext")
public record CreatePurchaseOrderRequest(
        @NotBlank @Schema(description = "ERPNext Supplier.name (document key)") String supplier,
        @Schema(description = "Transaction date (YYYY-MM-DD); defaults to today") String transactionDate,
        @Schema(description = "Expected delivery date (YYYY-MM-DD)") String scheduleDate,
        @Schema(description = "Additional notes / remarks") String notes,
        @NotEmpty @Valid @Schema(description = "Line items") List<LineItem> items
) {
    @Schema(description = "Purchase order line item")
    public record LineItem(
            @NotBlank @Schema(description = "ERPNext Item code") String itemCode,
            @NotNull @Positive @Schema(description = "Quantity") Double qty,
            @Schema(description = "Unit rate (optional; uses Item default if omitted)") Double rate
    ) {}
}
