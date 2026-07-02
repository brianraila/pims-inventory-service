package ke.co.safaricom.pims.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Supplier group")
public record SupplierGroupResponse(
        @Schema(description = "ERPNext Supplier Group name / id") String id,
        @Schema(description = "Supplier group name") String name,
        @Schema(description = "Parent supplier group") String parent,
        @Schema(description = "Whether this is a group (branch) node") boolean isGroup
) {}
