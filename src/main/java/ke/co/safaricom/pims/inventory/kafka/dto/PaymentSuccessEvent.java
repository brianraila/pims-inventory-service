package ke.co.safaricom.pims.inventory.kafka.dto;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @author AOmar
 */


@Data
@Builder
public class PaymentSuccessEvent {

    private String eventId;

    private String tenantId;

    private String orderId;

    private String status;

    private String paymentMethod;

    private BigDecimal amount;

    private String currency;

    private String transactionRef;

    private String payerPhone;

    private String paidAt;
}
