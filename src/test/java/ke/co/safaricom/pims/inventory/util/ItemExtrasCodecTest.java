package ke.co.safaricom.pims.inventory.util;

import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.util.ItemExtrasCodec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ItemExtrasCodecTest {

    private static final UUID MFR_ID = UUID.randomUUID();

    private InventoryApiSchemas.CreateProductRequest fullRequest() {
        return new InventoryApiSchemas.CreateProductRequest(
                "Amoxicillin 500mg",
                "Amoxicillin",
                MFR_ID,
                Enums.ProductCategory.Antibiotics,
                "PPB-001",
                "NDC-001",
                Enums.RegulatoryStatus.approved,
                "500mg",
                "Capsules",
                "Store below 25°C",
                "rxnorm",
                "term-123",
                Enums.UnitOfMeasure.capsules,
                50.0,
                500.0,
                new InventoryApiSchemas.SpecialRequirements(false, null, true, false, null),
                null);
    }

    @Test
    void embed_then_parse_roundtrips_all_fields() {
        InventoryApiSchemas.CreateProductRequest req = fullRequest();
        String encoded = ItemExtrasCodec.embed("Base description", req);

        Map<String, Object> parsed = ItemExtrasCodec.parse(encoded);

        assertThat(parsed.get("generic_name")).isEqualTo("Amoxicillin");
        assertThat(parsed.get("manufacturer_id")).isEqualTo(MFR_ID.toString());
        assertThat(parsed.get("category")).isEqualTo("Antibiotics");
        assertThat(parsed.get("ppb_code")).isEqualTo("PPB-001");
        assertThat(parsed.get("ndc_code")).isEqualTo("NDC-001");
        assertThat(parsed.get("strength")).isEqualTo("500mg");
        assertThat(parsed.get("dosage_form")).isEqualTo("Capsules");
        assertThat(parsed.get("terminology_source")).isEqualTo("rxnorm");
        assertThat(parsed.get("terminology_id")).isEqualTo("term-123");
    }

    @Test
    void parse_returns_empty_map_for_null_description() {
        assertThat(ItemExtrasCodec.parse(null)).isEmpty();
    }

    @Test
    void parse_returns_empty_map_for_blank_description() {
        assertThat(ItemExtrasCodec.parse("   ")).isEmpty();
    }

    @Test
    void parse_returns_empty_map_when_no_fragment_present() {
        assertThat(ItemExtrasCodec.parse("plain text description")).isEmpty();
    }

    @Test
    void strip_removes_fragment_and_keeps_human_text() {
        String encoded = ItemExtrasCodec.embed("Human readable part", fullRequest());
        String stripped = ItemExtrasCodec.strip(encoded);

        assertThat(stripped).isEqualTo("Human readable part");
        assertThat(stripped).doesNotContain("<<<PIMS_ITEM_EXTRAS>>>");
    }

    @Test
    void strip_returns_unchanged_text_when_no_fragment() {
        assertThat(ItemExtrasCodec.strip("just plain text")).isEqualTo("just plain text");
    }

    @Test
    void strip_returns_empty_string_for_null() {
        assertThat(ItemExtrasCodec.strip(null)).isEmpty();
    }

    @Test
    void mergeUpdate_overlays_only_non_null_fields() {
        String original = ItemExtrasCodec.embed("", fullRequest());

        InventoryApiSchemas.UpdateProductRequest update = new InventoryApiSchemas.UpdateProductRequest(
                null, "UpdatedGeneric", null, null,
                "PPB-NEW", null, null, null, null, null,
                null, null, null, null);

        String merged = ItemExtrasCodec.mergeUpdate(original, update);
        Map<String, Object> parsed = ItemExtrasCodec.parse(merged);

        assertThat(parsed.get("generic_name")).isEqualTo("UpdatedGeneric");
        assertThat(parsed.get("ppb_code")).isEqualTo("PPB-NEW");
        assertThat(parsed.get("ndc_code")).isEqualTo("NDC-001");
    }

    @Test
    void displayGenericName_prefers_mergedExtras() {
        Map<String, Object> extras = new HashMap<>();
        extras.put("generic_name", "From Extras");

        String result = ItemExtrasCodec.displayGenericName("<<<PIMS_ITEM_EXTRAS>>>{generic_name:From Fragment}<<<END_PIMS_ITEM_EXTRAS>>>", extras);
        assertThat(result).isEqualTo("From Extras");
    }

    @Test
    void displayGenericName_falls_back_to_fragment() {
        String encoded = ItemExtrasCodec.embed("", fullRequest());
        String result = ItemExtrasCodec.displayGenericName(encoded, Map.of());
        assertThat(result).isEqualTo("Amoxicillin");
    }

    @Test
    void displayGenericName_falls_back_to_stripped_text() {
        String result = ItemExtrasCodec.displayGenericName("Plain text, no fragment", Map.of());
        assertThat(result).isEqualTo("Plain text, no fragment");
    }

    @Test
    void reg_returns_approved_for_null() {
        assertThat(ItemExtrasCodec.reg(null)).isEqualTo(Enums.RegulatoryStatus.approved);
    }

    @Test
    void reg_parses_valid_enum_name() {
        assertThat(ItemExtrasCodec.reg("pending")).isEqualTo(Enums.RegulatoryStatus.pending);
        assertThat(ItemExtrasCodec.reg("suspended")).isEqualTo(Enums.RegulatoryStatus.suspended);
        assertThat(ItemExtrasCodec.reg("withdrawn")).isEqualTo(Enums.RegulatoryStatus.withdrawn);
    }

    @Test
    void reg_returns_approved_for_garbage_input() {
        assertThat(ItemExtrasCodec.reg("not_a_real_status")).isEqualTo(Enums.RegulatoryStatus.approved);
    }

    @Test
    void specials_maps_boolean_fields_from_map() {
        Map<String, Object> extras = new HashMap<>();
        Map<String, Object> sr = new HashMap<>();
        sr.put("controlled_substance", true);
        sr.put("cold_chain_required", false);
        sr.put("photosensitive", true);
        sr.put("photosensitive_shelf_life_months", 6);
        extras.put("special_requirements", sr);

        InventoryApiSchemas.SpecialRequirements result = ItemExtrasCodec.specials(extras);

        assertThat(result.controlledSubstance()).isTrue();
        assertThat(result.coldChainRequired()).isFalse();
        assertThat(result.photosensitive()).isTrue();
        assertThat(result.photosensitiveShelfLifeMonths()).isEqualTo(6);
    }

    @Test
    void specials_returns_defaults_when_no_special_requirements_key() {
        InventoryApiSchemas.SpecialRequirements result = ItemExtrasCodec.specials(Map.of());
        assertThat(result.controlledSubstance()).isFalse();
        assertThat(result.coldChainRequired()).isFalse();
        assertThat(result.photosensitive()).isFalse();
    }
}
