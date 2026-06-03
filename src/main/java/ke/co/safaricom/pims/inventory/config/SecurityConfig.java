package ke.co.safaricom.pims.inventory.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String DEFAULT_LOCAL_JWK_URI =
            "http://localhost:8080/realms/pims/protocol/openid-connect/certs";

    @Bean
    SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        .pathMatchers("/api/v1/inventory/v3/api-docs/**",
                                "/api/v1/inventory/swagger-ui/**",
                                "/api/v1/inventory/swagger-ui.html",
                                "/openapi/**").permitAll()
                        .pathMatchers("/api/v1/inventory/**").authenticated()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {
                }))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        String resolvedJwkSetUri = resolveJwkSetUri(jwkSetUri, issuerUri);
        logger.info("Using JWK set URI: {}", resolvedJwkSetUri);
        return NimbusReactiveJwtDecoder.withJwkSetUri(resolvedJwkSetUri).build();
    }

    private String resolveJwkSetUri(String jwkSetUri, String issuerUri) {
        if (StringUtils.hasText(jwkSetUri)) {
            logger.debug("Using explicit jwk-set-uri from configuration");
            return jwkSetUri;
        }

        if (StringUtils.hasText(issuerUri)) {
            String normalizedIssuer = issuerUri.endsWith("/") ? issuerUri : issuerUri + "/";
            String resolvedUri = normalizedIssuer + "protocol/openid-connect/certs";
            logger.debug("Derived JWK URI from issuer-uri: {}", resolvedUri);
            return resolvedUri;
        }

        logger.warn("No JWT issuer-uri or jwk-set-uri configured; falling back to local default URI");
        return DEFAULT_LOCAL_JWK_URI;
    }
}
