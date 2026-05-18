package ke.co.safaricom.pims.inventory.config;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class SecurityHeadersFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.add("X-Content-Type-Options", "nosniff");
        headers.add("X-Frame-Options", "DENY");
        headers.add("Cache-Control", "no-store");
        headers.add("Pragma", "no-cache");
        headers.add("X-XSS-Protection", "1; mode=block");
        headers.add("Referrer-Policy", "no-referrer");
        return chain.filter(exchange);
    }
}
