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
            String tenantId = jwtToken.getToken().getClaimAsString("organization");
            if (tenantId != null && !tenantId.isBlank()) {
                if (tenantId.startsWith("[") && tenantId.endsWith("]")) {
                    tenantId = tenantId.substring(1, tenantId.length() - 1);
                }
                return Mono.just(tenantId);
            }
        }

        String tenantHeader = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            if (tenantHeader.startsWith("[") && tenantHeader.endsWith("]")) {
                tenantHeader = tenantHeader.substring(1, tenantHeader.length() - 1);
            }
            return Mono.just(tenantHeader);
        }

        return Mono.error(new IllegalArgumentException(
                "Tenant context missing. Provide tenant_id claim in JWT or X-Tenant-Id header."));
    }
}
