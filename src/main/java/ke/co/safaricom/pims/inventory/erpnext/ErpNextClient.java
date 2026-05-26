package ke.co.safaricom.pims.inventory.erpnext;

import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Low-level reactive HTTP client for ERPNext REST API calls.
 * Each call receives an auth header to keep the client stateless.
 */
@Component
public class ErpNextClient {

    private static final Logger logger = LoggerFactory.getLogger(ErpNextClient.class);

    private final WebClient webClient;

    public ErpNextClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public <T> Mono<T> get(
            String baseUrl,
            HttpHeaders requestHeaders,
            String path,
            MultiValueMap<String, String> params,
            Class<T> responseType) {

        return webClient.mutate().baseUrl(baseUrl).build()
                .get()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(params).build())
                .headers(h -> h.addAll(requestHeaders))
                .retrieve()
                .bodyToMono(responseType)
                .onErrorMap(WebClientResponseException.class, this::mapHttpError);
    }

    public <T> Mono<T> post(
            String baseUrl,
            HttpHeaders requestHeaders,
            String path,
            Object body,
            Class<T> responseType) {

        return webClient.mutate().baseUrl(baseUrl).build()
                .post()
                .uri(uriBuilder -> uriBuilder.path(path).build())
                .headers(h -> h.addAll(requestHeaders))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType)
                .onErrorMap(WebClientResponseException.class, this::mapHttpError);
    }

    public <T> Mono<T> put(
            String baseUrl,
            HttpHeaders requestHeaders,
            String path,
            Object body,
            Class<T> responseType) {

        return webClient.mutate().baseUrl(baseUrl).build()
                .put()
                .uri(uriBuilder -> uriBuilder.path(path).build())
                .headers(h -> h.addAll(requestHeaders))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType)
                .onErrorMap(WebClientResponseException.class, this::mapHttpError);
    }

    public Mono<Void> delete(String baseUrl, HttpHeaders requestHeaders, String path) {
        return webClient.mutate().baseUrl(baseUrl).build()
                .delete()
                .uri(uriBuilder -> uriBuilder.path(path).build())
                .headers(h -> h.addAll(requestHeaders))
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorMap(WebClientResponseException.class, this::mapHttpError);
    }

    private Throwable mapHttpError(WebClientResponseException ex) {
        logger.warn("ERPNext API error: {} {}", ex.getStatusCode(), ex.getMessage());
        if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
            return new ResourceNotFoundException(ex.getMessage());
        }
        if (ex.getStatusCode() == HttpStatus.CONFLICT) {
            return new ConflictException(ex.getResponseBodyAsString());
        }
        if (ex.getStatusCode() == HttpStatus.BAD_REQUEST
                || ex.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
            return new ServiceValidationException(ex.getResponseBodyAsString());
        }
        return ex;
    }
}
