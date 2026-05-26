package ke.co.safaricom.pims.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Inventory item with current batch/stock information")
public record InventoryItemResponse(
        @Schema(description = "ERPNext Item name (document key)") String id,
        @Schema(description = "Item display name") String name,
        @Schema(description = "Generic / INN name (may mirror Item.description for extras parsing)") String genericName,
        @Schema(description = "Item group / category") String category,
        @Schema(description = "True if a controlled substance") boolean isControlled,
        @Schema(description = "True if requires cold-chain storage") boolean isColdChain,
        @Schema(description = "Temperature requirement (cold-chain items only)") String tempRequirement,
        @Schema(description = "Reorder level quantity") double reorderLevel,
        @Schema(description = "Maximum allowed stock quantity") double maxStock,
        @Schema(description = "Stock UOM") String unit,
        @Schema(description = "Active batches for this item") List<BatchResponse> batches,
        @Schema(
                description =
                        "PMIS keys sourced from ERPNext Item custom columns (custom_pims_*); merged with description fragment when reading") Map<String, Object> pimsCustomColumns
) {
    public InventoryItemResponse {
        batches = batches == null ? List.of() : batches;
        pimsCustomColumns = pimsCustomColumns == null ? Map.of() : Map.copyOf(pimsCustomColumns);
    }
}
