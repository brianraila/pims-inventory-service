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

        // ── Structured contact / licensing (parsed from supplier_details) ──
        @Schema(description = "Contact email") String email,
        @Schema(description = "Contact phone") String phone,
        @Schema(description = "County") String county,
        @Schema(description = "Street / physical address") String street,
        @Schema(description = "Licence number") String licenseNumber,
        @Schema(description = "Licence type") String licenseType,
        @Schema(description = "Licence validity / expiry") String licenseValidity,
        @Schema(description = "Ownership") String ownership,
        @Schema(description = "Free-text notes") String notes,

        @Schema(description = "Raw supplier details text") String details,
        @Schema(description = "Whether the supplier is disabled") boolean disabled,
        @Schema(description = "Status label: Active | Disabled") String status,

        // ── Audit ──
        @Schema(description = "User who created the supplier") String createdBy,
        @Schema(description = "User who last modified the supplier") String modifiedBy,
        @Schema(description = "Created timestamp") String createdAt,
        @Schema(description = "Last modified timestamp") String modifiedAt
) {}
