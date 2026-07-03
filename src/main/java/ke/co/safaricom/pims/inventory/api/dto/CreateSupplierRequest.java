package ke.co.safaricom.pims.inventory.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload to create or update an ERPNext Supplier for the tenant.
 *
 * <p>Only ERPNext-standard Supplier fields are written directly ({@code supplier_name},
 * {@code supplier_group}, {@code supplier_type}, {@code tax_id}, {@code country}). Contact and
 * PPB / licensing metadata are composed into the {@code supplier_details} note so writes never
 * fail on non-standard fields. {@code supplierGroup}/{@code supplierType} fall back to
 * configured defaults when omitted.
 */
@Schema(description = "Create or update a supplier")
public record CreateSupplierRequest(

        @Schema(description = "Supplier / facility name", example = "Lifeadd Chemist Limited",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 140) String supplierName,

        @Schema(description = "ERPNext Supplier Group; defaults to the configured group when omitted.")
        String supplierGroup,

        @Schema(description = "Supplier type: Company or Individual; defaults to the configured type.",
                allowableValues = {"Company", "Individual"})
        String supplierType,

        @Schema(description = "Facility / PPB registration number (stored as ERPNext tax_id).",
                example = "PPB/L/10072")
        @Size(max = 140) String registrationNumber,

        @Schema(description = "Licence number", example = "PPB/L/10072")
        String licenseNumber,

        @Schema(description = "Licence type", example = "Retail")
        String licenseType,

        @Schema(description = "Licence validity / expiry (as displayed)", example = "31/12/2026")
        String licenseValidity,

        @Schema(description = "Ownership", example = "Limited Company")
        String ownership,

        @Schema(description = "Contact email")
        @Email @Size(max = 140) String email,

        @Schema(description = "Contact phone")
        @Size(max = 40) String phone,

        @Schema(description = "County", example = "Nairobi")
        String county,

        @Schema(description = "Street / physical address")
        String street,

        @Schema(description = "Country", example = "Kenya")
        String country,

        @Schema(description = "Free-text notes appended to the supplier details.")
        String notes,

        @Schema(description = "Whether the supplier is disabled in ERPNext (default false).")
        Boolean disabled
) {
}
