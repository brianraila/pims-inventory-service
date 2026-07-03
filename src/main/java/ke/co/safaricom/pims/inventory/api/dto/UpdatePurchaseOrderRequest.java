package ke.co.safaricom.pims.inventory.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Update a DRAFT purchase order. Line items carry the real ERPNext item_code
 * (as returned by the detail endpoint), so no stable-id resolution is needed.
 */
@Schema(description = "Update a draft purchase order")
public record UpdatePurchaseOrderRequest(
        @Schema(description = "Required-by / expected delivery date (YYYY-MM-DD)") String scheduleDate,
        @Schema(description = "Notes / remarks") String notes,
        @NotEmpty @Valid @Schema(description = "Line items") List<Item> items
) {
    @Schema(description = "Purchase order line")
    public record Item(
            @NotBlank @Schema(description = "ERPNext item_code") String itemCode,
            @NotNull @Positive Double qty,
            @Schema(description = "Unit rate") Double rate
    ) {}
}
