package ke.co.safaricom.pims.inventory.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        List<FieldError> errors,
        Map<String, Object> details) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(String field, String message) {}

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, null, null);
    }

    public static ErrorResponse validation(ErrorCode code, String message, List<FieldError> errors) {
        return new ErrorResponse(code.name(), message, errors, Map.of());
    }

    public static ErrorResponse withDetails(ErrorCode code, String message, Map<String, Object> details) {
        return new ErrorResponse(code.name(), message, null, details);
    }
}
