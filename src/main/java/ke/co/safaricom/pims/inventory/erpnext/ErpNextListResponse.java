package ke.co.safaricom.pims.inventory.erpnext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ERPNext list endpoint envelope: { "data": [...] }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErpNextListResponse<T>(List<T> data) {}
