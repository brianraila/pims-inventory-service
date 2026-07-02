package ke.co.safaricom.pims.inventory.mapper;

import ke.co.safaricom.pims.inventory.api.dto.SupplierResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import org.springframework.stereotype.Component;

@Component
public class SupplierMapper {

    public SupplierResponse toResponse(ErpNextDoc doc) {
        boolean disabled = doc.disabled() != null && doc.disabled() == 1;
        return new SupplierResponse(
                doc.name(),
                doc.supplierName() != null ? doc.supplierName() : doc.name(),
                doc.supplierGroup(),
                doc.supplierType(),
                doc.taxId(),
                doc.country(),
                doc.supplierDetails(),
                disabled,
                disabled ? "Disabled" : "Active",
                doc.creation(),
                doc.modified()
        );
    }
}
