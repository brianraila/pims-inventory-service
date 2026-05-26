package ke.co.safaricom.pims.inventory.web.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class StableEntityIds {

    private StableEntityIds() {}

    public static UUID itemId(String tenantId, String erpItemName) {
        return UUID.nameUUIDFromBytes((tenantId + "::item::" + erpItemName).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID batchId(String tenantId, String erpBatchName) {
        return UUID.nameUUIDFromBytes((tenantId + "::batch::" + erpBatchName).getBytes(StandardCharsets.UTF_8));
    }
}
