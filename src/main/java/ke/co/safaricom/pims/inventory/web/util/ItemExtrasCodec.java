package ke.co.safaricom.pims.inventory.web.util;

import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;

import ke.co.safaricom.pims.inventory.web.model.Enums;
import java.util.HashMap;
import java.util.Map;

/** Serialises Inventory API-only fields into ERPNext Item.description (JSON fragment). */
public final class ItemExtrasCodec {

    private static final String PREFIX = "<<<PIMS_ITEM_EXTRAS>>>";
    private static final String SUFFIX = "<<<END_PIMS_ITEM_EXTRAS>>>";

    private static final String LEGACY_DELIM_HTML = "&lt;&lt;&gt;&gt;";
    private static final String LEGACY_DELIM_RAW  = "<<>>";

    private static final String KEY_GENERIC_NAME       = "generic_name";
    private static final String KEY_SPECIAL_REQUIREMENTS = "special_requirements";

    private ItemExtrasCodec() {}

    public static String embed(String baseDescription, InventoryApiSchemas.CreateProductRequest r) {
        String base = baseDescription == null ? "" : baseDescription;
        return base + "\n" + PREFIX + toMap(r) + SUFFIX;
    }

    public static String mergeUpdate(String currentDescription, InventoryApiSchemas.UpdateProductRequest u) {
        Map<String, Object> map = parse(currentDescription);
        if (u.productName() != null) {
            /* item_name updated separately */ }
        if (u.genericName() != null) map.put(KEY_GENERIC_NAME, u.genericName());
        if (u.manufacturerId() != null) map.put("manufacturer_id", u.manufacturerId().toString());
        if (u.category() != null) map.put("category", u.category());
        if (u.ppbCode() != null) map.put("ppb_code", u.ppbCode());
        if (u.ndcCode() != null) map.put("ndc_code", u.ndcCode());
        if (u.regulatoryStatus() != null) map.put("regulatory_status", u.regulatoryStatus().name());
        if (u.strength() != null) map.put("strength", u.strength());
        if (u.dosageForm() != null) map.put("dosage_form", u.dosageForm());
        if (u.additionalNotes() != null) map.put("additional_notes", u.additionalNotes());
        if (u.unitOfMeasure() != null) map.put("unit_of_measure", u.unitOfMeasure().jsonName());
        if (u.reorderLevel() != null) map.put("reorder_level", u.reorderLevel());
        if (u.maximumStock() != null) map.put("maximum_stock", u.maximumStock());
        if (u.specialRequirements() != null) map.put(KEY_SPECIAL_REQUIREMENTS, u.specialRequirements());
        String stripped = strip(currentDescription);
        return stripped + "\n" + PREFIX + map.toString().replace('=', ':') + SUFFIX;
    }

    /** Minimal JSON-ish serialisation avoiding extra deps (map toString suffices for controlled keys). */
    private static String toMap(InventoryApiSchemas.CreateProductRequest r) {
        Map<String, Object> m = new HashMap<>();
        if (r.genericName() != null) m.put(KEY_GENERIC_NAME, r.genericName());
        if (r.manufacturerId() != null) m.put("manufacturer_id", r.manufacturerId().toString());
        if (r.category() != null) m.put("category", r.category());
        if (r.ppbCode() != null) m.put("ppb_code", r.ppbCode());
        if (r.ndcCode() != null) m.put("ndc_code", r.ndcCode());
        if (r.regulatoryStatus() != null) m.put("regulatory_status", r.regulatoryStatus().name());
        if (r.strength() != null) m.put("strength", r.strength());
        if (r.dosageForm() != null) m.put("dosage_form", r.dosageForm());
        if (r.additionalNotes() != null) m.put("additional_notes", r.additionalNotes());
        if (r.terminologySource() != null) m.put("terminology_source", r.terminologySource());
        if (r.terminologyId() != null) m.put("terminology_id", r.terminologyId());
        if (r.unitOfMeasure() != null) m.put("unit_of_measure", r.unitOfMeasure().jsonName());
        if (r.reorderLevel() != null) m.put("reorder_level", r.reorderLevel());
        if (r.maximumStock() != null) m.put("maximum_stock", r.maximumStock());
        if (r.specialRequirements() != null) m.put(KEY_SPECIAL_REQUIREMENTS, r.specialRequirements());
        return m.toString().replace('=', ':');
    }

    public static Map<String, Object> parse(String description) {
        if (description == null) return new HashMap<>();
        // Try new format first
        int start = description.indexOf(PREFIX);
        int end = description.indexOf(SUFFIX);
        if (start >= 0 && end > start) {
            return parseLooseMap(description.substring(start + PREFIX.length(), end).trim());
        }
        return parseLegacy(description, LEGACY_DELIM_HTML, LEGACY_DELIM_RAW);
    }

    private static Map<String, Object> parseLegacy(String description, String... delims) {
        for (String delim : delims) {
            int first = description.indexOf(delim);
            if (first >= 0) {
                int second = description.indexOf(delim, first + delim.length());
                if (second >= 0) {
                    return parseLooseMap(description.substring(first + delim.length(), second).trim());
                }
            }
        }
        return new HashMap<>();
    }

    public static String strip(String description) {
        if (description == null) return "";
        int start = description.indexOf(PREFIX);
        if (start >= 0) return description.substring(0, start).trim();
        // Strip legacy format
        for (String delim : new String[]{LEGACY_DELIM_HTML, LEGACY_DELIM_RAW}) {
            int first = description.indexOf(delim);
            if (first >= 0) return description.substring(0, first).trim();
        }
        return description.trim();
    }

    /** Prefer merged extras generic_name; fall back to parsed fragment. Returns null when not found. */
    public static String displayGenericName(String rawDescription, Map<String, Object> mergedExtras) {
        if (mergedExtras != null) {
            Object g = mergedExtras.get(KEY_GENERIC_NAME);
            if (g != null && !String.valueOf(g).isBlank()) return String.valueOf(g);
        }
        Object g2 = parse(rawDescription).get(KEY_GENERIC_NAME);
        if (g2 != null && !String.valueOf(g2).isBlank()) return String.valueOf(g2);
        String stripped = strip(rawDescription);
        return stripped.isBlank() ? null : stripped;
    }

    private static Map<String, Object> parseLooseMap(String inner) {
        Map<String, Object> out = new HashMap<>();
        String s = inner.replace("{", "").replace("}", "");
        for (String part : s.split(", ")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            String k = part.substring(0, c).trim();
            String v = part.substring(c + 1).trim();
            out.put(k, unwrap(v));
        }
        return out;
    }

    private static Object unwrap(String v) {
        if (v.startsWith("'") && v.endsWith("'")) return v.substring(1, v.length() - 1);
        try {
            if (v.contains(".")) return Double.parseDouble(v);
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    public static InventoryApiSchemas.SpecialRequirements specials(Map<String, Object> extras) {
        Object sr = extras.get(KEY_SPECIAL_REQUIREMENTS);
        if (sr instanceof Map<?, ?> mm) {
            Boolean cs = mm.get("controlled_substance") instanceof Boolean b ? b : null;
            return new InventoryApiSchemas.SpecialRequirements(cs, str(mm.get("controlled_substance_schedule")),
                    mm.get("cold_chain_required") instanceof Boolean b2 ? b2 : null,
                    mm.get("photosensitive") instanceof Boolean b3 ? b3 : null,
                    mm.get("photosensitive_shelf_life_months") instanceof Integer i ? i : null);
        }
        if (sr instanceof InventoryApiSchemas.SpecialRequirements s) return s;
        return new InventoryApiSchemas.SpecialRequirements(false, null, false, false, null);
    }

    public static Enums.RegulatoryStatus reg(String raw) {
        if (raw == null) return Enums.RegulatoryStatus.approved;
        try {
            return Enums.RegulatoryStatus.valueOf(String.valueOf(raw).trim());
        } catch (Exception e) {
            return Enums.RegulatoryStatus.approved;
        }
    }

    public static Enums.UnitOfMeasure uom(String raw) {
        return Enums.UnitOfMeasure.fromItemUom(String.valueOf(raw));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
