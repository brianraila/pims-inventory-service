package ke.co.safaricom.pims.inventory.kafka.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSuccessEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialises_camelCase_payload_from_payment_service() throws Exception {
        String json = """
                {
                  "eventId": "evt-1",
                  "tenantId": "abc-pharmacy-westlands",
                  "orderId": "ACC-SINV-2026-00021",
                  "status": "SUCCESS",
                  "paymentMethod": "MPESA",
                  "amount": 116.00,
                  "currency": "KES",
                  "transactionRef": "QGR7XXXXX1",
                  "payerPhone": "254712345678",
                  "paidAt": "2026-06-07T20:56:40.316"
                }
                """;

        PaymentSuccessEvent event = mapper.readValue(json, PaymentSuccessEvent.class);

        assertThat(event.getEventId()).isEqualTo("evt-1");
        assertThat(event.getTenantId()).isEqualTo("abc-pharmacy-westlands");
        assertThat(event.getOrderId()).isEqualTo("ACC-SINV-2026-00021");
        assertThat(event.getPaymentMethod()).isEqualTo("MPESA");
        assertThat(event.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(116.0));
        assertThat(event.getTransactionRef()).isEqualTo("QGR7XXXXX1");
        assertThat(event.isSuccessful()).isTrue();
    }

    @Test
    void deserialises_snake_case_payload() throws Exception {
        String json = """
                {
                  "event_id": "evt-2",
                  "tenant_id": "abc-pharmacy-westlands",
                  "order_id": "ACC-SINV-2026-00022",
                  "status": "SUCCESS",
                  "payment_method": "mpesa",
                  "amount": 50.0,
                  "transaction_ref": "REF-2"
                }
                """;

        PaymentSuccessEvent event = mapper.readValue(json, PaymentSuccessEvent.class);

        assertThat(event.getEventId()).isEqualTo("evt-2");
        assertThat(event.getOrderId()).isEqualTo("ACC-SINV-2026-00022");
        assertThat(event.getPaymentMethod()).isEqualTo("mpesa");
    }
}
