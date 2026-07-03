package ke.co.safaricom.pims.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Purchase order detail with line items")
public record PurchaseOrderDetail(
        String id,
        String poNumber,
        String supplier,
        String orderDate,
        String expectedDate,
        String status,
        @Schema(description = "ERPNext docstatus: 0 = draft, 1 = submitted, 2 = cancelled") int docstatus,
        @Schema(description = "True once the PO has been submitted (no further edits allowed)") boolean submitted,
        String notes,
        double totalAmount,
        List<Item> items
) {
    @Schema(description = "Purchase order line")
    public record Item(
            @Schema(description = "ERPNext child-row name (purchase_order_item) — links a receipt back to this line")
            String rowId,
            String itemCode,
            String itemName,
            String uom,
            double qty,
            @Schema(description = "Quantity already received against this line") double receivedQty,
            double rate,
            double amount
    ) {}
}
