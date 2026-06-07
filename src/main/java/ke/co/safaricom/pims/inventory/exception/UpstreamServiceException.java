package ke.co.safaricom.pims.inventory.exception;

public class UpstreamServiceException extends RuntimeException {

    private final ErrorCode errorCode;

    public UpstreamServiceException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
