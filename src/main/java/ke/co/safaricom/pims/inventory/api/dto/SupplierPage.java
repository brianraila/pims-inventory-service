package ke.co.safaricom.pims.inventory.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "A page of suppliers")
public record SupplierPage(
        List<SupplierResponse> items,
        int page,
        int size,
        boolean hasNext,
        boolean hasPrevious
) {}
