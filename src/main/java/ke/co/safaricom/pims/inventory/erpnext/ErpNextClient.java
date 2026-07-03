package ke.co.safaricom.pims.inventory.erpnext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ErrorCode;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import ke.co.safaricom.pims.inventory.exception.UpstreamServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Low-level reactive HTTP client for ERPNext REST API calls.
 * Each call receives an auth header to keep the client stateless.
 */
@Component
public class ErpNextClient {

    private static final Logger logger = LoggerFactory.getLogger(ErpNextClient.class);

    /**
     * Retries transient connection failures (e.g. "Connection reset" on a reused pooled socket)
     * with exponential backoff. Only network-level errors are retried — HTTP error responses
     * ({@link WebClientResponseException}) are never retried. On exhaustion the ORIGINAL error is
     * rethrown so downstream handlers keep mapping it to a clean 503.
     */
    private static final Retry TRANSIENT_RETRY = Retry.backoff(3, Duration.ofMillis(300))
            .maxBackoff(Duration.ofSeconds(3))
            .filter(ErpNextClient::isTransientConnectionError)
            .onRetryExhaustedThrow((spec, signal) -> signal.failure());

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ErpNextClient(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    public <T> Mono<T> get(
            String baseUrl,
            HttpHeaders requestHeaders,
            String path,
            MultiValueMap<String, String> params,
            Class<T> responseType) {

        return webClient.mutate().baseUrl(baseUrl).build()
                .get()
                .uri(resolveUri(baseUrl, path, params))
                .headers(h -> h.addAll(requestHeaders))
                .retrieve()
                .bodyToMono(responseType)
                .retryWhen(TRANSIENT_RETRY)
                .onErrorMap(WebClientResponseException.class, this::mapHttpError);
    }

    public <T> Mono<T> get(
            String baseUrl,
            HttpHeaders requestHeaders,
            String path,
            MultiValueMap<String, String> params,
            ParameterizedTypeReference<T> responseType) {

        return webClient.mutate().baseUrl(baseUrl).build()
                .get()
                .uri(resolveUri(baseUrl, path, params))
                .headers(h -> h.addAll(requestHeaders))
                .retrieve()
                .bodyToMono(responseType)
                .retryWhen(TRANSIENT_RETRY)
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
                .uri(resolveUri(baseUrl, path, null))
                .headers(h -> h.addAll(requestHeaders))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType)
                .retryWhen(TRANSIENT_RETRY)
                .onErrorMap(WebClientResponseException.class, this::mapHttpError);
    }

    public <T> Mono<T> post(
            String baseUrl,
            HttpHeaders requestHeaders,
            String path,
            Object body,
            ParameterizedTypeReference<T> responseType) {

        return webClient.mutate().baseUrl(baseUrl).build()
                .post()
                .uri(resolveUri(baseUrl, path, null))
                .headers(h -> h.addAll(requestHeaders))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType)
                .retryWhen(TRANSIENT_RETRY)
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
                .uri(resolveUri(baseUrl, path, null))
                .headers(h -> h.addAll(requestHeaders))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType)
                .retryWhen(TRANSIENT_RETRY)
                .onErrorMap(WebClientResponseException.class, this::mapHttpError);
    }

    public <T> Mono<T> put(
            String baseUrl,
            HttpHeaders requestHeaders,
            String path,
            Object body,
            ParameterizedTypeReference<T> responseType) {

        return webClient.mutate().baseUrl(baseUrl).build()
                .put()
                .uri(resolveUri(baseUrl, path, null))
                .headers(h -> h.addAll(requestHeaders))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType)
                .retryWhen(TRANSIENT_RETRY)
                .onErrorMap(WebClientResponseException.class, this::mapHttpError);
    }

    public Mono<Void> delete(String baseUrl, HttpHeaders requestHeaders, String path) {
        return webClient.mutate().baseUrl(baseUrl).build()
                .delete()
                .uri(resolveUri(baseUrl, path, null))
                .headers(h -> h.addAll(requestHeaders))
                .retrieve()
                .bodyToMono(Void.class)
                .retryWhen(TRANSIENT_RETRY)
                .onErrorMap(WebClientResponseException.class, this::mapHttpError);
    }

    /**
     * True for network-level failures worth retrying — a connection reset / premature close /
     * broken pipe on a reused pooled socket. HTTP error responses are excluded (never retried).
     */
    private static boolean isTransientConnectionError(Throwable error) {
        if (error instanceof WebClientResponseException) {
            return false;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientRequestException || cause instanceof IOException) {
                return true;
            }
            // reactor.netty.http.client.PrematureCloseException (avoid a hard dependency).
            if (cause.getClass().getName().contains("PrematureCloseException")) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    private URI resolveUri(String baseUrl, String path, MultiValueMap<String, String> queryParams) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (queryParams == null || queryParams.isEmpty()) {
            return URI.create(normalizedBase + path);
        }
        StringBuilder query = new StringBuilder();
        queryParams.forEach((key, values) -> values.forEach(value -> {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(UriUtils.encodeQueryParam(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8));
        }));
        return URI.create(normalizedBase + path + '?' + query);
    }

    private Throwable mapHttpError(WebClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        logger.warn("ERPNext API error: {} - {}", ex.getStatusCode(), body);

        if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
            String msg = extractErpNextMessage(body, null);
            return new ResourceNotFoundException(msg != null ? msg : "The requested resource was not found.");
        }
        if (ex.getStatusCode() == HttpStatus.CONFLICT) {
            return new ConflictException(extractErpNextMessage(body, "A conflict occurred with the existing data."));
        }
        if (ex.getStatusCode() == HttpStatus.BAD_REQUEST
                || ex.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY
                || ex.getStatusCode().value() == 417) {
            return new ServiceValidationException(extractErpNextMessage(body, "The request could not be processed. Please check your inputs."));
        }
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status != null && (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN)) {
            return new UpstreamServiceException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "Unable to authenticate with ERPNext. Please contact support.");
        }
        if (status != null && status.is5xxServerError()) {
            return new UpstreamServiceException(
                    ErrorCode.UPSTREAM_ERROR,
                    "ERPNext service error (" + status.value() + "). Please try again later.");
        }
        return new UpstreamServiceException(
                ErrorCode.UPSTREAM_ERROR,
                "An unexpected error occurred while communicating with ERPNext.");
    }

    /**
     * Parses an ERPNext error response body to extract the user-facing message.
     * ERPNext puts user messages in {@code _server_messages} as a JSON-encoded array
     * of objects with a {@code message} field. Falls back to the {@code exception}
     * field (text after the last ": ") when server messages are absent.
     */
    private String extractErpNextMessage(String body, String fallback) {
        try {
            JsonNode root = objectMapper.readTree(body);

            // _server_messages is a JSON string containing an array of message objects
            String rawServerMsgs = root.path("_server_messages").asText("");
            if (!rawServerMsgs.isBlank()) {
                JsonNode msgs = objectMapper.readTree(rawServerMsgs);
                if (msgs.isArray() && !msgs.isEmpty()) {
                    JsonNode first = msgs.get(0);
                    // Each element may be a JSON object or a JSON-encoded object string
                    JsonNode msgNode = first.isObject() ? first : objectMapper.readTree(first.asText("{}"));
                    String msg = msgNode.path("message").asText("");
                    msg = msg.replaceAll("<[^>]+>", "").trim();
                    if (!msg.isBlank()) return msg;
                }
            }

            // Fall back to the exception field — take the part after the last ": "
            String exc = root.path("exception").asText("");
            if (!exc.isBlank() && exc.contains(": ")) {
                return exc.substring(exc.lastIndexOf(": ") + 2).trim();
            }
        } catch (Exception parseEx) {
            logger.debug("Could not parse ERPNext error body", parseEx);
        }
        return fallback;
    }
}
