package ke.co.safaricom.pims.inventory.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import ke.co.safaricom.pims.inventory.api.dto.SupplierResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SupplierMapperTest {

    private final SupplierMapper mapper = new SupplierMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toResponse_parses_supplier_details_into_structured_fields() throws Exception {
        String json = """
                {
                  "name": "SUP-001",
                  "supplier_name": "Lifeadd Chemist Limited",
                  "supplier_group": "All Supplier Groups",
                  "supplier_type": "Company",
                  "tax_id": "PPB/L/10072",
                  "country": "Kenya",
                  "supplier_details": "Licence No: PPB/L/10072\\nLicence Type: Retail\\nLicence Validity: 31/12/2026\\nOwnership: Limited Company\\nEmail: info@lifeadd.co.ke\\nPhone: +254712345678\\nCounty: Nairobi\\nStreet: Westlands Road\\nNotes: Created via test",
                  "disabled": 0,
                  "owner": "admin",
                  "modified_by": "admin",
                  "creation": "2026-01-01 10:00:00",
                  "modified": "2026-01-02 10:00:00"
                }
                """;

        ErpNextDoc doc = objectMapper.readValue(json, ErpNextDoc.class);
        SupplierResponse response = mapper.toResponse(doc);

        assertEquals("SUP-001", response.id());
        assertEquals("Lifeadd Chemist Limited", response.supplierName());
        assertEquals("PPB/L/10072", response.registrationNumber());
        assertEquals("PPB/L/10072", response.licenseNumber());
        assertEquals("Retail", response.licenseType());
        assertEquals("31/12/2026", response.licenseValidity());
        assertEquals("Limited Company", response.ownership());
        assertEquals("info@lifeadd.co.ke", response.email());
        assertEquals("+254712345678", response.phone());
        assertEquals("Nairobi", response.county());
        assertEquals("Westlands Road", response.street());
        assertEquals("Created via test", response.notes());
        assertFalse(response.disabled());
        assertEquals("Active", response.status());
    }
}
