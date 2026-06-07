package ke.co.safaricom.pims.inventory.kafka.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Kafka contract consumed from {@code ms-pims-payment-service}. That producer serialises with
 * default Jackson camelCase ({@code eventId}, {@code orderId}, …); {@link JsonAlias} keeps
 * snake_case payloads deserializable too.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentSuccessEvent {

    @JsonAlias("event_id")
    private String eventId;

    @JsonAlias("tenant_id")
    private String tenantId;

    @JsonAlias("order_id")
    private String orderId;

    private String status;

    @JsonAlias("payment_method")
    private String paymentMethod;

    private BigDecimal amount;

    private String currency;

    @JsonAlias("transaction_ref")
    private String transactionRef;

    @JsonAlias("payer_phone")
    private String payerPhone;

    @JsonAlias("paid_at")
    private String paidAt;

    public boolean isSuccessful() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}
