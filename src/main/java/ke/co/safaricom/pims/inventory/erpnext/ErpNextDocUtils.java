package ke.co.safaricom.pims.inventory.erpnext;

import java.util.List;
import java.util.Map;

/**
 * Stateless helpers for patching ERPNext document payloads before submission.
 */
public final class ErpNextDocUtils {

    private ErpNextDocUtils() {}

    /**
     * ERPNext blocks submission when a line item has {@code basic_rate = 0} unless
     * {@code allow_zero_valuation_rate} is explicitly set to {@code 1}. This is common for
     * items that carry no purchase cost (e.g. donated stock or ARVs supplied at zero cost).
     *
     * <p>Call this method on the raw document map retrieved from ERPNext immediately before
     * passing it to {@code frappe.client.submit}.
     */
    @SuppressWarnings("unchecked")
    public static void allowZeroValuationRateOnZeroCostItems(Map<String, Object> doc) {
        Object itemsObj = doc.get("items");
        if (!(itemsObj instanceof List<?> rawList)) return;
        for (Object raw : rawList) {
            if (!(raw instanceof Map)) continue;
            Map<String, Object> item = (Map<String, Object>) raw;
            Object rate = item.get("basic_rate");
            boolean isZeroCost;
            if (rate instanceof Number n) {
                isZeroCost = n.doubleValue() == 0.0;
            } else {
                isZeroCost = false; // absent means valuation unknown, not zero
            }
            if (isZeroCost) {
                item.put("allow_zero_valuation_rate", 1);
            }
        }
    }
}
