package ke.co.safaricom.pims.inventory.erpnext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ERPNext single-document endpoint envelope: { "data": { ... } }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErpNextSingleResponse<T>(T data) {}
