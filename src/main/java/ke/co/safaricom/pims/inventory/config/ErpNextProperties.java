package ke.co.safaricom.pims.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * ERPNext connection properties.
 * Tenant base URL: {scheme}://{tenantId}.{domain}[:{port}]
 * Per-tenant API key/secret can be set under erpnext.tenants.{tenantId}.api-key/api-secret.
 * Falls back to erpnext.api-key / erpnext.api-secret if no tenant-specific credentials exist.
 *
 * When erpnext.wrapper.url is set, all ERP calls are routed through the wrapper service instead.
 * The wrapper resolves tenant routing internally via X-Tenant-Id header.
 * erpnext.wrapper.tenant-id is an optional static override (e.g. for dev); absent in prod so the
 * JWT-resolved tenant flows through dynamically.
 */
@ConfigurationProperties(prefix = "erpnext")
public record ErpNextProperties(
        String domain,
        String apiKey,
        String apiSecret,
        String scheme,
        Integer port,
        String defaultWarehouse,
        Map<String, TenantCredentials> tenants,
        WrapperConfig wrapper
) {
    public ErpNextProperties {
        if (scheme == null || scheme.isBlank()) scheme = "https";
        if (defaultWarehouse == null || defaultWarehouse.isBlank()) defaultWarehouse = "Stores - S";
        if (tenants == null) tenants = Map.of();
    }

    public String baseUrlForTenant(String tenantId) {
        String base = scheme + "://" + tenantId + "." + domain;
        if (port != null) base += ":" + port;
        return base;
    }

    public String apiKeyFor(String tenantId) {
        TenantCredentials creds = tenants.get(tenantId);
        return (creds != null && creds.apiKey() != null) ? creds.apiKey() : apiKey;
    }

    public String apiSecretFor(String tenantId) {
        TenantCredentials creds = tenants.get(tenantId);
        return (creds != null && creds.apiSecret() != null) ? creds.apiSecret() : apiSecret;
    }

    public record TenantCredentials(String apiKey, String apiSecret) {}

    public record WrapperConfig(String url, String tenantId) {}
}
