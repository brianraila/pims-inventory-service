package ke.co.safaricom.pims.inventory.web.service;

import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory product wizard drafts keyed by tenant. */
@Component
public class ProductDraftMemoryStore {

    private final ConcurrentHashMap<String, InventoryApiSchemas.ProductDraft> store = new ConcurrentHashMap<>();

    private static String key(String tenantId, UUID draftId) {
        return tenantId + ":" + draftId;
    }

    public InventoryApiSchemas.ProductDraft create(String tenantId, InventoryApiSchemas.ProductDraftRequest req) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        InventoryApiSchemas.CreateProductRequest data = normalizeDraftData(req.data());
        InventoryApiSchemas.ProductDraft d = new InventoryApiSchemas.ProductDraft(id, req.wizardStep(), data, now.toString(), now.toString());
        store.put(key(tenantId, id), d);
        return d;
    }

    private static InventoryApiSchemas.CreateProductRequest normalizeDraftData(InventoryApiSchemas.CreateProductRequest data) {
        return data != null ? data : new InventoryApiSchemas.CreateProductRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.Collections.emptyList());
    }

    public Optional<InventoryApiSchemas.ProductDraft> get(String tenantId, UUID draftId) {
        return Optional.ofNullable(store.get(key(tenantId, draftId)));
    }

    public InventoryApiSchemas.ProductDraft update(String tenantId, UUID draftId, InventoryApiSchemas.ProductDraftRequest req) {
        String k = key(tenantId, draftId);
        InventoryApiSchemas.ProductDraft prev = Optional.ofNullable(store.get(k)).orElseThrow(() -> new ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException("Draft not found"));
        InventoryApiSchemas.CreateProductRequest merged = mergeDraft(prev.data(), req.data(), req.wizardStep());
        InventoryApiSchemas.ProductDraft next =
                new InventoryApiSchemas.ProductDraft(draftId, req.wizardStep(), merged, prev.createdAt(), Instant.now().toString());
        store.put(k, next);
        return next;
    }

    /** Shallow-merge draft payload (non-null fields from incoming win). */
    private InventoryApiSchemas.CreateProductRequest mergeDraft(InventoryApiSchemas.CreateProductRequest base, InventoryApiSchemas.CreateProductRequest incoming, ke.co.safaricom.pims.inventory.web.model.Enums.WizardStep step) {
        if (incoming == null) return base;
        return new InventoryApiSchemas.CreateProductRequest(
                coalesce(incoming.productName(), base.productName()),
                coalesce(incoming.genericName(), base.genericName()),
                coalesce(incoming.manufacturerId(), base.manufacturerId()),
                coalesce(incoming.category(), base.category()),
                coalesce(incoming.ppbCode(), base.ppbCode()),
                coalesce(incoming.ndcCode(), base.ndcCode()),
                coalesce(incoming.regulatoryStatus(), base.regulatoryStatus()),
                coalesce(incoming.strength(), base.strength()),
                coalesce(incoming.dosageForm(), base.dosageForm()),
                coalesce(incoming.additionalNotes(), base.additionalNotes()),
                coalesce(incoming.terminologySource(), base.terminologySource()),
                coalesce(incoming.terminologyId(), base.terminologyId()),
                coalesce(incoming.unitOfMeasure(), base.unitOfMeasure()),
                coalesce(incoming.reorderLevel(), base.reorderLevel()),
                coalesce(incoming.maximumStock(), base.maximumStock()),
                coalesce(incoming.specialRequirements(), base.specialRequirements()),
                (incoming.initialBatches() != null && !incoming.initialBatches().isEmpty())
                        ? incoming.initialBatches()
                        : base.initialBatches());
    }

    private static <T> T coalesce(T a, T b) {
        return a != null ? a : b;
    }
}
