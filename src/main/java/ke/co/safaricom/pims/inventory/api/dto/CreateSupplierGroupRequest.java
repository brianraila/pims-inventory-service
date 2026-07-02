package ke.co.safaricom.pims.inventory.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Create a supplier group")
public record CreateSupplierGroupRequest(

        @Schema(description = "Supplier group name", example = "Wholesale Distributors",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 140) String name,

        @Schema(description = "Parent supplier group; defaults to the configured root group when omitted.")
        String parent
) {
}
