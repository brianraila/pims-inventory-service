package ke.co.safaricom.pims.inventory.util;

import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.util.ManufacturersCatalog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManufacturersCatalogTest {

    @Test
    void filtered_no_args_returns_all_five_manufacturers() {
        List<InventoryApiSchemas.Manufacturer> result = ManufacturersCatalog.filtered(null, null);
        assertThat(result).hasSize(5);
    }

    @Test
    void filtered_by_name_returns_matching_manufacturer() {
        List<InventoryApiSchemas.Manufacturer> result = ManufacturersCatalog.filtered("Teva", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Teva Pharmaceuticals");
    }

    @Test
    void filtered_search_is_case_insensitive() {
        List<InventoryApiSchemas.Manufacturer> result = ManufacturersCatalog.filtered("gsk", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("GSK plc");
    }

    @Test
    void filtered_by_country_substring_returns_kenya_manufacturers() {
        List<InventoryApiSchemas.Manufacturer> result = ManufacturersCatalog.filtered("dawa", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).country()).isEqualTo("KE");
    }

    @Test
    void filtered_with_limit_caps_result() {
        List<InventoryApiSchemas.Manufacturer> result = ManufacturersCatalog.filtered(null, 2);
        assertThat(result).hasSize(2);
    }

    @Test
    void filtered_with_limit_zero_defaults_to_fifty() {
        List<InventoryApiSchemas.Manufacturer> result = ManufacturersCatalog.filtered(null, 0);
        assertThat(result).hasSize(5);
    }

    @Test
    void filtered_with_no_match_returns_empty() {
        List<InventoryApiSchemas.Manufacturer> result = ManufacturersCatalog.filtered("XYZ_NONEXISTENT", null);
        assertThat(result).isEmpty();
    }

    @Test
    void all_manufacturers_are_active() {
        ManufacturersCatalog.filtered(null, null)
                .forEach(m -> assertThat(m.isActive()).isTrue());
    }

    @Test
    void byId_returns_manufacturer_for_known_id() {
        UUID tevaId = UUID.nameUUIDFromBytes("mfr|Teva Pharmaceuticals".getBytes(StandardCharsets.UTF_8));
        InventoryApiSchemas.Manufacturer result = ManufacturersCatalog.byId(tevaId);
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("Teva Pharmaceuticals");
    }

    @Test
    void byId_returns_null_for_unknown_id() {
        InventoryApiSchemas.Manufacturer result = ManufacturersCatalog.byId(UUID.randomUUID());
        assertThat(result).isNull();
    }

    @Test
    void manufacturers_have_stable_deterministic_ids() {
        UUID id1 = ManufacturersCatalog.filtered("GSK", null).get(0).id();
        UUID id2 = ManufacturersCatalog.filtered("GSK", null).get(0).id();
        assertThat(id1).isEqualTo(id2);
    }
}
