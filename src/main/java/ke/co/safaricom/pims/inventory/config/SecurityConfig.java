package ke.co.safaricom.pims.inventory.config;

import ke.co.safaricom.pims.inventory.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.StringUtils;

@Profile("!dev")
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String DEFAULT_LOCAL_JWK_URI =
            "http://localhost:8080/realms/pims/protocol/openid-connect/certs";

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        .pathMatchers("/openapi/**").permitAll()
                        .pathMatchers(
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/api/v1/inventory/v3/api-docs",
                                "/api/v1/inventory/v3/api-docs/**",
                                "/api/v1/inventory/swagger-ui/**"
                                "/swagger-ui.html",
                                "/swagger-ui/**").permitAll()
                        .pathMatchers(Constants.API_PREFIX + "/**").authenticated()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        String resolvedUri = resolveJwkSetUri(jwkSetUri, issuerUri);
        logger.info("Using JWK set URI: {}", resolvedUri);
        return NimbusReactiveJwtDecoder.withJwkSetUri(resolvedUri).build();
    }

    private String resolveJwkSetUri(String jwkSetUri, String issuerUri) {
        if (StringUtils.hasText(jwkSetUri)) {
            return jwkSetUri;
        }
        if (StringUtils.hasText(issuerUri)) {
            String normalized = issuerUri.endsWith("/") ? issuerUri : issuerUri + "/";
            return normalized + "protocol/openid-connect/certs";
        }
        logger.warn("No JWT issuer-uri or jwk-set-uri configured; falling back to local default URI");
        return DEFAULT_LOCAL_JWK_URI;
    }
}
