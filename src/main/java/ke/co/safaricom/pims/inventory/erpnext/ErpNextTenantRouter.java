package ke.co.safaricom.pims.inventory.erpnext;

import ke.co.safaricom.pims.inventory.config.ErpNextProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Resolves tenant ID → ERPNext base URL and dispatches authenticated REST calls.
 * Per-tenant credentials are looked up from erpnext.tenants.{tenantId}; falls
 * back to the global erpnext.api-key / erpnext.api-secret when not configured.
 */
@Component
public class ErpNextTenantRouter {

    private static final String RESOURCE_API = "/api/resource";

    private final ErpNextProperties properties;
    private final ErpNextClient client;

    public ErpNextTenantRouter(ErpNextProperties properties, ErpNextClient client) {
        this.properties = properties;
        this.client = client;
    }

    // ---- GET list -----------------------------------------------------------

    public <T> Mono<T> getList(String tenantId, String doctype, Map<String, String> queryParams, Class<T> type) {
        return client.get(resolveBaseUrl(tenantId), resolveHeaders(tenantId), resourcePath(doctype), toMultiValue(queryParams), type);
    }

    public <T> Mono<T> getList(String tenantId, String doctype, Map<String, String> queryParams, ParameterizedTypeReference<T> type) {
        return client.get(resolveBaseUrl(tenantId), resolveHeaders(tenantId), resourcePath(doctype), toMultiValue(queryParams), type);
    }

    // ---- GET single ---------------------------------------------------------

    public <T> Mono<T> getOne(String tenantId, String doctype, String name, Class<T> type) {
        return client.get(
                resolveBaseUrl(tenantId),
                resolveHeaders(tenantId),
                resourcePath(doctype) + "/" + name,
                new LinkedMultiValueMap<>(),
                type);
    }

    public <T> Mono<T> getOne(String tenantId, String doctype, String name, ParameterizedTypeReference<T> type) {
        return client.get(
                resolveBaseUrl(tenantId),
                resolveHeaders(tenantId),
                resourcePath(doctype) + "/" + name,
                new LinkedMultiValueMap<>(),
                type);
    }

    // ---- POST (create) ------------------------------------------------------

    public <T> Mono<T> create(String tenantId, String doctype, Object body, Class<T> type) {
        return client.post(resolveBaseUrl(tenantId), resolveHeaders(tenantId), resourcePath(doctype), body, type);
    }

    public <T> Mono<T> create(String tenantId, String doctype, Object body, ParameterizedTypeReference<T> type) {
        return client.post(resolveBaseUrl(tenantId), resolveHeaders(tenantId), resourcePath(doctype), body, type);
    }

    // ---- PUT (update entire document) --------------------------------------

    public <T> Mono<T> replace(String tenantId, String doctype, String name, Object body, Class<T> type) {
        String path = resourcePath(doctype) + "/" + name;
        return client.put(resolveBaseUrl(tenantId), resolveHeaders(tenantId), path, body, type);
    }

    public <T> Mono<T> replace(String tenantId, String doctype, String name, Object body, ParameterizedTypeReference<T> type) {
        String path = resourcePath(doctype) + "/" + name;
        return client.put(resolveBaseUrl(tenantId), resolveHeaders(tenantId), path, body, type);
    }

    // ---- Frappe method call -------------------------------------------------

    public <T> Mono<T> callMethod(String tenantId, String method, Object body, ParameterizedTypeReference<T> type) {
        return client.post(resolveBaseUrl(tenantId), resolveHeaders(tenantId),
                "/api/method/" + method, body, type);
    }

    // ---- DELETE -------------------------------------------------------------

    public Mono<Void> delete(String tenantId, String doctype, String name) {
        String path = resourcePath(doctype) + "/" + name;
        return client.delete(resolveBaseUrl(tenantId), resolveHeaders(tenantId), path);
    }

    // ---- helpers ------------------------------------------------------------

    private String resolveBaseUrl(String tenantId) {
        ErpNextProperties.WrapperConfig wrapper = properties.wrapper();
        if (wrapper != null && wrapper.url() != null) {
            return wrapper.url();
        }
        return properties.baseUrlForTenant(tenantId);
    }

    private HttpHeaders resolveHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        ErpNextProperties.WrapperConfig wrapper = properties.wrapper();
        if (wrapper != null && wrapper.url() != null) {
            String effectiveTenant = (wrapper.tenantId() != null) ? wrapper.tenantId() : tenantId;
            headers.set("X-Tenant-Id", effectiveTenant);
        } else {
            headers.set(HttpHeaders.AUTHORIZATION,
                    "token " + properties.apiKeyFor(tenantId) + ":" + properties.apiSecretFor(tenantId));
        }
        return headers;
    }

    private String resourcePath(String doctype) {
        return RESOURCE_API + "/" + doctype;
    }

    private MultiValueMap<String, String> toMultiValue(Map<String, String> queryParams) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        queryParams.forEach(params::add);
        if (!params.containsKey("limit_page_length")) {
            params.add("limit_page_length", "500");
        }
        return params;
    }
}
