package ke.co.safaricom.pims.inventory.web.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** DTOs for the Sales Taxes and Charges Template management API. */
public final class TaxSchemas {

    private TaxSchemas() {}

    // ---- Requests -----------------------------------------------------------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateTaxTemplateRequest(
            @NotBlank String title,
            @NotBlank String company,
            Boolean isDefault,
            @NotEmpty @Valid List<TaxChargeRow> taxes
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateTaxTemplateRequest(
            String title,
            String company,
            Boolean isDefault,
            Boolean disabled,
            @Valid List<TaxChargeRow> taxes
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaxChargeRow(
            /** On Net Total | Actual | On Previous Row Amount | On Previous Row Total | On Item Quantity */
            @NotBlank String chargeType,
            /** ERPNext Account name with account_type = Tax */
            @NotBlank String accountHead,
            @NotNull Double rate,
            String description,
            /** Add or Deduct — use Deduct for withholding tax */
            String addDeductTax,
            Boolean includedInPrintRate
    ) {}

    // ---- Responses ----------------------------------------------------------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaxTemplateResponse(
            String name,
            String title,
            String company,
            Boolean isDefault,
            Boolean disabled,
            List<TaxChargeRow> taxes
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TaxTemplateListResponse(List<TaxTemplateResponse> data) {}
}
