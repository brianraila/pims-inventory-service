package ke.co.safaricom.pims.inventory.exception;

public class ServiceValidationException extends RuntimeException {

    private final ErrorCode errorCode;

    public ServiceValidationException(String message) {
        this(ErrorCode.BAD_REQUEST, message);
    }

    public ServiceValidationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
