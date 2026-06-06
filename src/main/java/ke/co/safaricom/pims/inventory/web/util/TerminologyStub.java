package ke.co.safaricom.pims.inventory.web.util;

import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.model.Enums.TerminologyRecordSource;
import ke.co.safaricom.pims.inventory.web.model.Enums.TerminologySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Placeholder RxNorm/PPB-style records until external terminology is integrated. */
public final class TerminologyStub {

    private TerminologyStub() {}

    public static InventoryApiSchemas.TerminologySearchResponse search(String q, UUID manufacturerId, TerminologySource source, int limit) {
        int lim = limit < 1 ? 10 : Math.min(limit, 100);
        List<InventoryApiSchemas.TerminologySearchResult> data = new ArrayList<>();
        if (q == null || q.length() < 2) {
            return new InventoryApiSchemas.TerminologySearchResponse(List.of(), 0, sourcesQueried(source));
        }
        String needle = q.toLowerCase(Locale.ROOT);
        if (source == null || source == TerminologySource.all || source == TerminologySource.rxnorm) {
            if (needle.contains("amoxicillin") || needle.contains("amox")) {
                data.add(new InventoryApiSchemas.TerminologySearchResult(
                        "rxn-1001",
                        TerminologyRecordSource.rxnorm,
                        "Amoxicillin 500mg Capsules",
                        "Amoxicillin",
                        "Teva Pharmaceuticals",
                        "0900-0100-01",
                        "500mg",
                        "Capsules"));
            }
        }
        if (source == null || source == TerminologySource.all || source == TerminologySource.ppb_ndc) {
            if (needle.contains("ppb") || needle.contains("amox")) {
                data.add(new InventoryApiSchemas.TerminologySearchResult(
                        "ppb-2001",
                        TerminologyRecordSource.ppb_ndc,
                        "Amoxicillin 500mg Capsules",
                        "Amoxicillin",
                        "Aspen Pharma",
                        "00093-4157-01",
                        "500mg",
                        "Capsules"));
            }
        }
        if (manufacturerId != null) {
            InventoryApiSchemas.Manufacturer m = ManufacturersCatalog.byId(manufacturerId);
            if (m != null) {
                data.removeIf(r -> !m.name().equalsIgnoreCase(r.manufacturerName()));
            }
        }
        return new InventoryApiSchemas.TerminologySearchResponse(
                data.stream().limit(lim).toList(), data.size(), sourcesQueried(source));
    }

    private static List<TerminologyRecordSource> sourcesQueried(TerminologySource source) {
        if (source == null || source == TerminologySource.all) {
            return List.of(TerminologyRecordSource.rxnorm, TerminologyRecordSource.ppb_ndc);
        }
        return List.of(
                source == TerminologySource.rxnorm
                        ? TerminologyRecordSource.rxnorm
                        : TerminologyRecordSource.ppb_ndc);
    }

    /**
     * Resolve a terminology product by id.
     *
     * <p>Curated mappings for the two ids returned by {@link #search} round-trip
     * to the same brand/manufacturer the search advertised. For any other
     * non-empty id (a numeric PPB code like {@code 723}, an opaque RxNorm
     * identifier, etc.) we synthesise a deterministic placeholder so consumers
     * can always exercise the endpoint while the real terminology integration
     * is pending. An empty/blank id returns {@link java.util.Optional#empty()},
     * which the service surfaces as a 404.</p>
     */
    public static java.util.Optional<InventoryApiSchemas.TerminologyProduct> product(String terminologyId) {
        if (terminologyId == null || terminologyId.isBlank()) {
            return java.util.Optional.empty();
        }
        String id = terminologyId.trim();
        if ("rxn-1001".equals(id)) {
            InventoryApiSchemas.Manufacturer m = ManufacturersCatalog.filtered("Teva", 1).get(0);
            return java.util.Optional.of(new InventoryApiSchemas.TerminologyProduct(
                    id,
                    TerminologyRecordSource.rxnorm,
                    "Amoxicillin 500mg Capsules",
                    "Amoxicillin",
                    m,
                    "Antibiotics",
                    "00093-4157-01",
                    "0900-0100-01",
                    Enums.RegulatoryStatus.approved,
                    "500mg",
                    "Capsules"));
        }
        if ("ppb-2001".equals(id)) {
            InventoryApiSchemas.Manufacturer m = ManufacturersCatalog.filtered("Aspen", 1).get(0);
            return java.util.Optional.of(new InventoryApiSchemas.TerminologyProduct(
                    id,
                    TerminologyRecordSource.ppb_ndc,
                    "Amoxicillin 500mg Capsules",
                    "Amoxicillin",
                    m,
                    "Antibiotics",
                    "00093-4157-01",
                    "0900-0100-01",
                    Enums.RegulatoryStatus.approved,
                    "500mg",
                    "Capsules"));
        }
        return java.util.Optional.of(synthesise(id));
    }

    /**
     * Build a deterministic placeholder TerminologyProduct for an arbitrary id
     * (e.g. {@code "723"}). The same id always yields the same record so
     * UI demos and tests stay stable.
     */
    private static InventoryApiSchemas.TerminologyProduct synthesise(String id) {
        TerminologyRecordSource src = inferSource(id);
        List<InventoryApiSchemas.Manufacturer> top = ManufacturersCatalog.filtered(null, 50);
        InventoryApiSchemas.Manufacturer manufacturer = top.isEmpty()
                ? null
                : top.get(Math.floorMod(id.hashCode(), top.size()));
        String label = "Terminology Product " + id;
        return new InventoryApiSchemas.TerminologyProduct(
                id,
                src,
                label,
                label,
                manufacturer,
                "Other",
                src == TerminologyRecordSource.ppb_ndc ? id : null,
                src == TerminologyRecordSource.rxnorm ? id : null,
                Enums.RegulatoryStatus.approved,
                null,
                null);
    }

    private static TerminologyRecordSource inferSource(String id) {
        String lc = id.toLowerCase(Locale.ROOT);
        if (lc.startsWith("rxn-") || lc.startsWith("rxnorm")) return TerminologyRecordSource.rxnorm;
        if (lc.startsWith("ppb-") || lc.startsWith("ndc-")) return TerminologyRecordSource.ppb_ndc;
        // Pure numeric ids default to PPB-style codes, the common pattern in KE.
        return id.chars().allMatch(Character::isDigit)
                ? TerminologyRecordSource.ppb_ndc
                : TerminologyRecordSource.rxnorm;
    }
}
