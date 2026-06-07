package ke.co.safaricom.pims.inventory.exception.handler;

import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ErrorCode;
import ke.co.safaricom.pims.inventory.exception.ErrorResponse;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import ke.co.safaricom.pims.inventory.exception.UpstreamServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException ex, ServerWebExchange exchange) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.add(new ErrorResponse.FieldError(
                fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "")));
        String message = errors.isEmpty() ? "Validation failed" : errors.get(0).message();
        logClientError(exchange, ex, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.validation(ErrorCode.VALIDATION_ERROR, message, errors));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, ServerWebExchange exchange) {
        logClientError(exchange, ex, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex, ServerWebExchange exchange) {
        logClientError(exchange, ex, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(ServiceValidationException.class)
    public ResponseEntity<ErrorResponse> handleServiceValidation(
            ServiceValidationException ex, ServerWebExchange exchange) {
        logClientError(exchange, ex, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(UpstreamServiceException.class)
    public ResponseEntity<ErrorResponse> handleUpstream(UpstreamServiceException ex, ServerWebExchange exchange) {
        logClientError(exchange, ex, ex.getMessage());
        HttpStatus status = ex.getErrorCode() == ErrorCode.SERVICE_UNAVAILABLE
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(WebClientRequestException.class)
    public ResponseEntity<ErrorResponse> handleWebClientRequest(
            WebClientRequestException ex, ServerWebExchange exchange) {
        logClientError(exchange, ex, ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(
                        ErrorCode.SERVICE_UNAVAILABLE, "Unable to reach ERPNext. Please try again later."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, ServerWebExchange exchange) {
        logger.warn(
                "Access denied on {} {}: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(ErrorCode.FORBIDDEN, "You do not have permission."));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> handleBadInput(ServerWebInputException ex, ServerWebExchange exchange) {
        String msg = "Invalid request body or parameter.";
        if (ex.getCause() instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException ife
                && !ife.getPath().isEmpty()) {
            String field = ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            msg = "Invalid value '" + ife.getValue() + "' for field '" + field + "'.";
        }
        logClientError(exchange, ex, msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(ErrorCode.BAD_REQUEST, msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, ServerWebExchange exchange) {
        logClientError(exchange, ex, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(ErrorCode.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<ErrorResponse> handleUncheckedIO(UncheckedIOException ex, ServerWebExchange exchange) {
        logger.error(
                "IO error on {} {}: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.getMessage(),
                ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, ServerWebExchange exchange) {
        logger.error(
                "Unhandled exception on {} {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.withDetails(
                        ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", Map.of()));
    }

    private static void logClientError(ServerWebExchange exchange, Throwable ex, String message) {
        logger.warn(
                "Client error on {} {}: {} ({})",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                message,
                ex.getClass().getSimpleName());
    }
}
