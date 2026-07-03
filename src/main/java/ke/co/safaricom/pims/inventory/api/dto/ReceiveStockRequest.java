package ke.co.safaricom.pims.inventory.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Receive stock against a submitted purchase order (creates + submits an ERPNext Purchase Receipt,
 * which posts stock and advances the PO's per_received).
 */
@Schema(description = "Receive stock against a purchase order")
public record ReceiveStockRequest(
        @Schema(description = "Warehouse to receive into; defaults to the tenant's configured warehouse.")
        String warehouse,

        @Schema(description = "Posting date (YYYY-MM-DD); defaults to today.")
        String postingDate,

        @Schema(description = "Supplier delivery note / invoice reference (optional).")
        String supplierDeliveryNote,

        @NotEmpty @Valid @Schema(description = "Lines received (a subset is allowed for partial receipts).")
        List<Line> items
) {
    @Schema(description = "A received line")
    public record Line(
            @Schema(description = "Linked PO child-row name (purchase_order_item).") String rowId,
            @NotBlank @Schema(description = "ERPNext item_code") String itemCode,
            @NotNull @Positive @Schema(description = "Quantity received now") Double qty,
            @Schema(description = "Batch number (required for batch-tracked items).") String batchNo,
            @Schema(description = "Batch expiry date (YYYY-MM-DD), optional.") String expiryDate
    ) {}
}
