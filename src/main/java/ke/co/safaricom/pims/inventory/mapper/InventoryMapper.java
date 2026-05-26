package ke.co.safaricom.pims.inventory.mapper;

import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.api.dto.StockAdjustmentResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class InventoryMapper {

    public InventoryItemResponse toItemResponse(ErpNextDoc doc) {
        Map<String, Object> pimsCols = pimsExtrasFromDoc(doc);
        boolean controlled =
                doc.customPimsControlledSubstance() != null && doc.customPimsControlledSubstance() == 1;
        boolean cold = doc.customPimsColdChainRequired() != null && doc.customPimsColdChainRequired() == 1;
        return new InventoryItemResponse(
                doc.name(),
                doc.itemName() != null ? doc.itemName() : doc.name(),
                safeStr(doc.description()),
                safeStr(doc.itemGroup()),
                controlled,
                cold,
                null,
                reorderLevel(doc),
                maxStockLevel(doc),
                unitOfMeasure(doc),
                Collections.emptyList(),
                pimsCols
        );
    }

    public InventoryItemResponse toItemResponseWithBatches(ErpNextDoc item, List<BatchResponse> batches) {
        return copyWithBatches(toItemResponse(item), batches);
    }

    public InventoryItemResponse copyWithBatches(InventoryItemResponse base, List<BatchResponse> batches) {
        return new InventoryItemResponse(
                base.id(),
                base.name(),
                base.genericName(),
                base.category(),
                base.isControlled(),
                base.isColdChain(),
                base.tempRequirement(),
                base.reorderLevel(),
                base.maxStock(),
                base.unit(),
                batches == null ? Collections.emptyList() : batches,
                base.pimsCustomColumns());
    }

    public BatchResponse toBatchResponse(ErpNextDoc doc) {
        String status = deriveBatchStatus(doc.expiryDate());
        return new BatchResponse(
                doc.name(),
                doc.batchId() != null ? doc.batchId() : doc.name(),
                safeStr(doc.itemCode()),
                doc.actualQty() != null ? doc.actualQty() : 0,
                status,
                safeStr(doc.expiryDate()),
                null,
                safeStr(doc.warehouse()),
                null,
                safeStr(doc.manufacturingDate()),
                0,
                safeStr(doc.supplier()),
                null
        );
    }

    public StockAdjustmentResponse toAdjustmentResponse(ErpNextDoc doc) {
        String type = "Material Receipt".equals(doc.purpose()) ? "addition" : "reduction";
        double qty = firstItemQty(doc);
        String itemCode = firstItemCode(doc);
        return new StockAdjustmentResponse(
                doc.name(),
                doc.name(),
                itemCode,
                type,
                qty,
                safeStr(doc.remarks()),
                safeStr(doc.postingDate()),
                safeStr(doc.owner())
        );
    }

    /** Maps ERPNext Item custom_pims_* columns to the same keys used inside ItemExtrasCodec description fragments. */
    public static Map<String, Object> pimsExtrasFromDoc(ErpNextDoc doc) {
        Map<String, Object> m = new HashMap<>();
        putIfPresent(m, "ppb_code", doc.customPimsPpbCode());
        putIfPresent(m, "ndc_code", doc.customPimsNdcCode());
        putIfPresent(m, "regulatory_status", doc.customPimsRegulatoryStatus());
        putIfPresent(m, "strength", doc.customPimsStrength());
        putIfPresent(m, "dosage_form", doc.customPimsDosageForm());
        putIfPresent(m, "terminology_source", doc.customPimsTerminologySource());
        putIfPresent(m, "terminology_id", doc.customPimsTerminologyId());
        putIfPresent(m, "additional_notes", doc.customPimsAdditionalNotes());
        putIfPresent(m, "manufacturer_id", doc.customPimsManufacturerUuid());
        putIfPresent(m, "generic_name", doc.customPimsGenericName());
        if (doc.customPimsSupplier() != null && !doc.customPimsSupplier().isBlank()) {
            m.put("pims_supplier", doc.customPimsSupplier());
        }
        putIfPresent(m, "unit_of_measure", doc.customPimsUnitOfMeasure());
        if (doc.customPimsReorderLevel() != null) {
            m.put("reorder_level", doc.customPimsReorderLevel());
        }
        if (doc.customPimsMaximumStock() != null) {
            m.put("maximum_stock", doc.customPimsMaximumStock());
        }

        Map<String, Object> sr = new HashMap<>();
        if (doc.customPimsControlledSubstance() != null) {
            sr.put("controlled_substance", doc.customPimsControlledSubstance() == 1);
        }
        putIfPresent(sr, "controlled_substance_schedule", doc.customPimsControlledSchedule());
        if (doc.customPimsColdChainRequired() != null) {
            sr.put("cold_chain_required", doc.customPimsColdChainRequired() == 1);
        }
        if (doc.customPimsPhotosensitive() != null) {
            sr.put("photosensitive", doc.customPimsPhotosensitive() == 1);
        }
        if (doc.customPimsPhotosensitiveShelfLifeMonths() != null) {
            sr.put("photosensitive_shelf_life_months", doc.customPimsPhotosensitiveShelfLifeMonths());
        }
        if (!sr.isEmpty()) {
            m.put("special_requirements", sr);
        }

        return Map.copyOf(m);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s && s.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    // ---- helpers ------------------------------------------------------------

    private String safeStr(String value) {
        return value != null ? value : "";
    }

    private String unitOfMeasure(ErpNextDoc doc) {
        if (doc.customPimsUnitOfMeasure() != null && !doc.customPimsUnitOfMeasure().isBlank()) {
            return doc.customPimsUnitOfMeasure();
        }
        return safeStr(doc.stockUom());
    }

    private double reorderLevel(ErpNextDoc doc) {
        if (doc.customPimsReorderLevel() != null) {
            return doc.customPimsReorderLevel();
        }
        if (doc.reorderLevels() == null || doc.reorderLevels().isEmpty()) return 0;
        Object val = doc.reorderLevels().get(0).get("warehouse_reorder_level");
        if (val instanceof Number n) return n.doubleValue();
        return 0;
    }

    private double maxStockLevel(ErpNextDoc doc) {
        if (doc.customPimsMaximumStock() != null) {
            return doc.customPimsMaximumStock();
        }
        return 0;
    }

    private double firstItemQty(ErpNextDoc doc) {
        if (doc.items() == null || doc.items().isEmpty()) return 0;
        Object val = doc.items().get(0).get("qty");
        if (val instanceof Number n) return n.doubleValue();
        return 0;
    }

    private String firstItemCode(ErpNextDoc doc) {
        if (doc.items() == null || doc.items().isEmpty()) return "";
        Object val = doc.items().get(0).get("item_code");
        return val != null ? val.toString() : "";
    }

    private String deriveBatchStatus(String expiryDate) {
        if (expiryDate == null || expiryDate.isBlank()) return "available";
        try {
            LocalDate expiry = LocalDate.parse(expiryDate);
            LocalDate today = LocalDate.now();
            if (expiry.isBefore(today)) return "expired";
            if (expiry.isBefore(today.plusDays(90))) return "expiring-soon";
            return "available";
        } catch (Exception e) {
            return "available";
        }
    }
}
