package ke.co.safaricom.pims.inventory.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TenantContextResolver {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    public Mono<String> resolveTenantId(Authentication authentication, ServerWebExchange exchange) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            String tenantId = jwtToken.getToken().getClaimAsString("tenant_id");
            if (tenantId == null || tenantId.isBlank()) {
                tenantId = jwtToken.getToken().getClaimAsString("tenantId");
            }
            if (tenantId != null && !tenantId.isBlank()) {
                return Mono.just(tenantId);
            }
        }

        String tenantHeader = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            return Mono.just(tenantHeader);
        }

        return Mono.error(new IllegalArgumentException(
                "Tenant context missing. Provide tenant_id claim in JWT or X-Tenant-Id header."));
    }
}
