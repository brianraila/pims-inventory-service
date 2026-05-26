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

    public static java.util.Optional<InventoryApiSchemas.TerminologyProduct> product(String terminologyId) {
        if ("rxn-1001".equals(terminologyId)) {
            InventoryApiSchemas.Manufacturer m = ManufacturersCatalog.filtered("Teva", 1).get(0);
            return java.util.Optional.of(new InventoryApiSchemas.TerminologyProduct(
                    terminologyId,
                    TerminologyRecordSource.rxnorm,
                    "Amoxicillin 500mg Capsules",
                    "Amoxicillin",
                    m,
                    Enums.ProductCategory.Antibiotics,
                    "00093-4157-01",
                    "0900-0100-01",
                    Enums.RegulatoryStatus.approved,
                    "500mg",
                    "Capsules"));
        }
        if ("ppb-2001".equals(terminologyId)) {
            InventoryApiSchemas.Manufacturer m = ManufacturersCatalog.filtered("Aspen", 1).get(0);
            return java.util.Optional.of(new InventoryApiSchemas.TerminologyProduct(
                    terminologyId,
                    TerminologyRecordSource.ppb_ndc,
                    "Amoxicillin 500mg Capsules",
                    "Amoxicillin",
                    m,
                    Enums.ProductCategory.Antibiotics,
                    "00093-4157-01",
                    "0900-0100-01",
                    Enums.RegulatoryStatus.approved,
                    "500mg",
                    "Capsules"));
        }
        return java.util.Optional.empty();
    }
}
