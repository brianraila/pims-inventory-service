package ke.co.safaricom.pims.inventory.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A purchase receipt posted against a purchase order")
public record ReceiptSummary(
        @Schema(description = "Purchase Receipt id") String receiptId,
        @Schema(description = "Posting/created timestamp") String postedAt,
        @Schema(description = "Total quantity received in this receipt") double qty
) {}
