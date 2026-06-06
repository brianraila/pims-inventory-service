package ke.co.safaricom.pims.inventory.erpnext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ERPNext RPC-method response envelope: { "message": ... } — used for {@code /api/method/*}
 * calls (as opposed to the {@code data} envelope returned by {@code /api/resource/*}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErpNextMessageResponse<T>(T message) {}
