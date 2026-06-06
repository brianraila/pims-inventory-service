package ke.co.safaricom.pims.inventory.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
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

    public boolean isSuccessful() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}
