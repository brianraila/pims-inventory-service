package ke.co.safaricom.pims.inventory.web.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public final class Enums {

    private Enums() {}

    public enum ProductCategory {
        Antibiotics,
        Analgesics,
        Diabetes,
        Cardiovascular,
        Gastrointestinal,
        Antifungals,
        Antivirals,
        Vitamins,
        Other;

        public static ProductCategory looseValueOf(String name) {
            if (name == null || name.isBlank()) return Other;
            for (ProductCategory c : values()) {
                if (c.name().equalsIgnoreCase(name.strip())) return c;
            }
            return Other;
        }
    }

    public enum ProductStatus {
        draft,
        available,
        low_stock,
        out_of_stock,
        controlled,
        recalled,
        expiring_soon,
        unknown
    }

    public enum BatchStatus {
        available,
        active,
        expired,
        recalled,
        quarantine,
        unavailable
    }

    public enum RegulatoryStatus {
        approved,
        pending,
        suspended,
        withdrawn
    }

    public enum UnitOfMeasure {
        tablets,
        capsules,
        vials,
        bottles,
        sachets,
        ampoules,
        tubes,
        units;

        @JsonCreator
        public static UnitOfMeasure fromJson(String raw) {
            if (raw == null || raw.isBlank()) return units;
            for (UnitOfMeasure u : values()) {
                if (u.jsonName().equalsIgnoreCase(raw.strip())) return u;
            }
            return units;
        }

        public static UnitOfMeasure fromItemUom(String uom) {
            if (uom == null) return units;
            String n = uom.trim().replace(' ', '_').toLowerCase();
            return fromJson(n.replace("tablet", "tablets"));
        }

        @JsonValue
        public String jsonName() {
            return name();
        }
    }

    public enum AdjustmentReason {
        damaged,
        expired,
        theft,
        correction,
        return_to_supplier,
        internal_transfer,
        write_off,
        other
    }

    public enum WizardStep {
        ONE(1),
        TWO(2),
        THREE(3),
        FOUR(4);

        private final int n;

        WizardStep(int n) {
            this.n = n;
        }

        public int stepNumber() {
            return n;
        }

        @JsonCreator
        public static WizardStep from(Integer v) {
            if (v == null) throw new IllegalArgumentException("wizard_step is required");
            for (WizardStep s : values()) {
                if (s.n == v) return s;
            }
            throw new IllegalArgumentException("Invalid wizard_step: " + v);
        }

        @JsonValue
        public int toJson() {
            return n;
        }
    }

    public enum TerminologySource {
        rxnorm,
        ppb_ndc,
        all
    }

    public enum AdjustmentDirection {
        increase,
        decrease
    }

    public enum TerminologyRecordSource {
        rxnorm,
        ppb_ndc
    }
}
