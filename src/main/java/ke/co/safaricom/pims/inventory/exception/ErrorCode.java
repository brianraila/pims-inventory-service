package ke.co.safaricom.pims.inventory.exception;

/** Machine-readable error codes returned in API error responses. */
public enum ErrorCode {
    VALIDATION_ERROR,
    BAD_REQUEST,
    NOT_FOUND,
    PRODUCT_NOT_FOUND,
    BATCH_NOT_FOUND,
    DRAFT_NOT_FOUND,
    TERMINOLOGY_NOT_FOUND,
    CONFLICT,
    FORBIDDEN,
    UPSTREAM_ERROR,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
