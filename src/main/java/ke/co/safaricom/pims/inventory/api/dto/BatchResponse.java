package ke.co.safaricom.pims.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Stock batch details")
public record BatchResponse(
        @Schema(description = "ERPNext Batch name") String id,
        @Schema(description = "Batch number") String batchNumber,
        @Schema(description = "Parent item code") String productId,
        @Schema(description = "Current quantity in stock") double quantity,
        @Schema(description = "Stock status: available | expiring-soon | expired | quarantined | recalled | reserved") String status,
        @Schema(description = "Expiry date (YYYY-MM-DD)") String expiryDate,
        @Schema(description = "Manufacturer name") String manufacturer,
        @Schema(description = "Warehouse / storage location") String location,
        @Schema(description = "Branch identifier") String branch,
        @Schema(description = "Date received (YYYY-MM-DD)") String receivedDate,
        @Schema(description = "Unit cost") double cost,
        @Schema(description = "Supplier name") String supplier,
        @Schema(description = "Goods Receipt Note reference") String grn
) {}
