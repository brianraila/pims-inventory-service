package ke.co.safaricom.pims.inventory.kafka.Consumer;

import ke.co.safaricom.pims.inventory.kafka.dto.PaymentSuccessEvent;
import ke.co.safaricom.pims.inventory.service.OrderPaymentService;
import ke.co.safaricom.pims.inventory.service.PaymentDetails;
import ke.co.safaricom.pims.inventory.web.model.SalesOrderSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentKafkaConsumerTest {

    private static final String TENANT = "abc-pharmacy";
    private static final String ORDER_ID = "SINV-2024-00001";

    @Mock
    private OrderPaymentService orderPaymentService;

    private PaymentKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentKafkaConsumer(orderPaymentService);
    }

    private static PaymentSuccessEvent event(String status) {
        return PaymentSuccessEvent.builder()
                .eventId("evt-1")
                .tenantId(TENANT)
                .orderId(ORDER_ID)
                .status(status)
                .paymentMethod("mpesa")
                .amount(BigDecimal.valueOf(1500.0))
                .currency("KES")
                .transactionRef("QGR7XXXXX1")
                .payerPhone("254712345678")
                .paidAt("2026-06-06T10:15:30Z")
                .build();
    }

    @Test
    void onPaymentSuccess_records_payment_for_successful_event() {
        SalesOrderSchemas.PaymentResponse response = new SalesOrderSchemas.PaymentResponse(
                ORDER_ID, "recorded", "mpesa", 1500.0, 0.0, "QGR7XXXXX1", "PE-0001");
        when(orderPaymentService.recordPayment(eq(TENANT), eq(ORDER_ID), any(PaymentDetails.class)))
                .thenReturn(Mono.just(response));

        consumer.onPaymentSuccess(event("SUCCESS"));

        verify(orderPaymentService).recordPayment(eq(TENANT), eq(ORDER_ID), argThat(details ->
                "mpesa".equals(details.paymentMethod())
                        && details.amountTendered() == 1500.0
                        && "QGR7XXXXX1".equals(details.transactionRef())
                        && "254712345678".equals(details.payerPhone())
                        && "2026-06-06T10:15:30Z".equals(details.paidAt())));
    }

    @Test
    void onPaymentSuccess_skips_failed_events_without_recording_payment() {
        consumer.onPaymentSuccess(event("FAILED"));

        verify(orderPaymentService, never()).recordPayment(any(), any(), any());
    }

    @Test
    void onPaymentSuccess_swallows_exceptions_so_consumer_keeps_running() {
        when(orderPaymentService.recordPayment(eq(TENANT), eq(ORDER_ID), any(PaymentDetails.class)))
                .thenReturn(Mono.error(new RuntimeException("ERPNext unavailable")));

        assertThatCode(() -> consumer.onPaymentSuccess(event("SUCCESS"))).doesNotThrowAnyException();
    }
}
