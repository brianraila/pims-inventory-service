package ke.co.safaricom.pims.inventory.config;

import ke.co.safaricom.pims.inventory.Constants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Profile("!dev")
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

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
                                "/api/v1/inventory/swagger-ui/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**").permitAll()
                        .pathMatchers(Constants.API_PREFIX + "/**").authenticated()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .build();
    }
}
