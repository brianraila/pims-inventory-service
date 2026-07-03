package ke.co.safaricom.pims.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Purchase order summary")
public record PurchaseOrderResponse(
        @Schema(description = "ERPNext Purchase Order name") String id,
        @Schema(description = "PO document number") String poNumber,
        @Schema(description = "Supplier name") String supplier,
        @Schema(description = "Order date (YYYY-MM-DD)") String orderDate,
        @Schema(description = "Expected delivery date (YYYY-MM-DD)") String expectedDate,
        @Schema(description = "Date goods were received (YYYY-MM-DD)") String receivedDate,
        @Schema(description = "Status: Draft | To Receive and Bill | To Bill | Completed | Cancelled") String status,
        @Schema(description = "Number of line items") int items,
        @Schema(description = "Total quantity across all lines") double totalQty,
        @Schema(description = "Grand total amount") double totalAmount,
        @Schema(description = "Percentage of the order received (0-100)") double perReceived,
        @Schema(description = "Percentage of the order billed/invoiced (0-100)") double perBilled
) {}
