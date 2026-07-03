package ke.co.safaricom.pims.inventory.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        // ERPNext (and the reverse proxy in front of it) silently close idle keep-alive
        // connections. Netty would otherwise hand a stale socket to the next request and
        // fail with "Connection reset". Evicting idle/aged connections BEFORE the server
        // drops them is the actual fix; SO_KEEPALIVE guards long-lived sockets.
        ConnectionProvider connectionProvider = ConnectionProvider.builder("erpnext")
                .maxConnections(50)
                .maxIdleTime(Duration.ofSeconds(10))   // shorter than the upstream idle timeout
                .maxLifeTime(Duration.ofSeconds(60))
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .evictInBackground(Duration.ofSeconds(15))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                // JVM DNS resolver — the Netty async resolver's native lib fails on macOS.
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .followRedirect(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn -> {
                    conn.addHandlerLast(new ReadTimeoutHandler(30));
                    conn.addHandlerLast(new WriteTimeoutHandler(30));
                });

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
