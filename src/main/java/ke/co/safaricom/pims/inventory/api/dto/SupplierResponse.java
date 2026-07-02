package ke.co.safaricom.pims.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Supplier detail")
public record SupplierResponse(
        @Schema(description = "ERPNext Supplier name / id") String id,
        @Schema(description = "Supplier / facility name") String supplierName,
        @Schema(description = "ERPNext Supplier Group") String supplierGroup,
        @Schema(description = "Supplier type (Company | Individual)") String supplierType,
        @Schema(description = "Facility / PPB registration number (ERPNext tax_id)") String registrationNumber,
        @Schema(description = "Country") String country,
        @Schema(description = "Supplier details / notes") String details,
        @Schema(description = "Whether the supplier is disabled") boolean disabled,
        @Schema(description = "Status label: Active | Disabled") String status,
        @Schema(description = "Created timestamp") String createdAt,
        @Schema(description = "Last modified timestamp") String modifiedAt
) {}
