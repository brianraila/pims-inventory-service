package ke.co.safaricom.pims.inventory.mapper;

import ke.co.safaricom.pims.inventory.api.dto.SupplierResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class SupplierMapper {

    public SupplierResponse toResponse(ErpNextDoc doc) {
        boolean disabled = doc.disabled() != null && doc.disabled() == 1;
        Map<String, String> d = parseDetails(doc.supplierDetails());
        return new SupplierResponse(
                doc.name(),
                doc.supplierName() != null ? doc.supplierName() : doc.name(),
                doc.supplierGroup(),
                doc.supplierType(),
                doc.taxId(),
                doc.country(),
                d.get("email"),
                d.get("phone"),
                d.get("county"),
                d.get("street"),
                d.get("licence no"),
                d.get("licence type"),
                d.get("licence validity"),
                d.get("ownership"),
                d.get("notes"),
                doc.supplierDetails(),
                disabled,
                disabled ? "Disabled" : "Active",
                doc.owner(),
                doc.modifiedBy(),
                doc.creation(),
                doc.modified()
        );
    }

    /**
     * Parses the {@code supplier_details} note (composed as "Label: value" lines by the
     * write path) back into structured fields, keyed by lower-cased label.
     */
    private Map<String, String> parseDetails(String details) {
        Map<String, String> map = new HashMap<>();
        if (details == null || details.isBlank()) return map;
        for (String line : details.split("\\r?\\n")) {
            int idx = line.indexOf(':');
            if (idx <= 0) continue;
            String label = line.substring(0, idx).trim().toLowerCase();
            String value = line.substring(idx + 1).trim();
            if (!value.isEmpty()) map.put(label, value);
        }
        return map;
    }
}
