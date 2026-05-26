package ke.co.safaricom.pims.inventory.web.util;

import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Seeded catalogue for GET /manufacturers and terminology manufacturer joins. */
public final class ManufacturersCatalog {

    private ManufacturersCatalog() {}

    private static final List<InventoryApiSchemas.Manufacturer> ALL = List.of(
            manufacturer("Teva Pharmaceuticals", "IL"),
            manufacturer("GSK plc", "GB"),
            manufacturer("Aspen Pharma", "ZA"),
            manufacturer("Laboratory & Allied", "KE"),
            manufacturer("Dawa Limited", "KE"));

    private static InventoryApiSchemas.Manufacturer manufacturer(String name, String country) {
        UUID id = UUID.nameUUIDFromBytes(("mfr|" + name).getBytes(UTF_8));
        return new InventoryApiSchemas.Manufacturer(id, name, country, true);
    }

    public static List<InventoryApiSchemas.Manufacturer> filtered(String query, Integer limit) {
        int lim = limit == null || limit < 1 ? 50 : Math.min(limit, 100);
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<InventoryApiSchemas.Manufacturer> list = ALL.stream()
                .filter(m -> q.isEmpty() || m.name().toLowerCase(Locale.ROOT).contains(q))
                .limit(lim)
                .toList();
        return list;
    }

    public static InventoryApiSchemas.Manufacturer byId(UUID id) {
        return ALL.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }
}
