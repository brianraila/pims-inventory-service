package ke.co.safaricom.pims.inventory.messaging;

import ke.co.safaricom.pims.inventory.service.OrderPaymentService;
import ke.co.safaricom.pims.inventory.service.PaymentDetails;
import ke.co.safaricom.pims.inventory.web.model.SalesOrderSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

    private static final String TENANT = "abc-pharmacy";
    private static final String ORDER_ID = "SINV-2024-00001";

    @Mock
    private OrderPaymentService orderPaymentService;

    private PaymentEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentEventListener(orderPaymentService);
    }

    private static PaymentEvent event(String status) {
        return new PaymentEvent("evt-1", TENANT, ORDER_ID, status, "mpesa",
                1500.0, "KES", "QGR7XXXXX1", "254712345678", "2026-06-06T10:15:30Z");
    }

    @Test
    void onPaymentEvent_records_payment_for_successful_event() {
        SalesOrderSchemas.PaymentResponse response = new SalesOrderSchemas.PaymentResponse(
                ORDER_ID, "recorded", "mpesa", 1500.0, 0.0, "QGR7XXXXX1", "PE-0001");
        when(orderPaymentService.recordPayment(eq(TENANT), eq(ORDER_ID), any(PaymentDetails.class)))
                .thenReturn(Mono.just(response));

        listener.onPaymentEvent(event("SUCCESS"));

        verify(orderPaymentService).recordPayment(eq(TENANT), eq(ORDER_ID), argThat(details ->
                "mpesa".equals(details.paymentMethod())
                        && details.amountTendered() == 1500.0
                        && "QGR7XXXXX1".equals(details.transactionRef())
                        && "254712345678".equals(details.payerPhone())
                        && "2026-06-06T10:15:30Z".equals(details.paidAt())));
    }

    @Test
    void onPaymentEvent_skips_failed_events_without_recording_payment() {
        listener.onPaymentEvent(event("FAILED"));

        verify(orderPaymentService, never()).recordPayment(any(), any(), any());
    }

    @Test
    void onPaymentEvent_swallows_exceptions_so_consumer_keeps_running() {
        when(orderPaymentService.recordPayment(eq(TENANT), eq(ORDER_ID), any(PaymentDetails.class)))
                .thenReturn(Mono.error(new RuntimeException("ERPNext unavailable")));

        assertThatCode(() -> listener.onPaymentEvent(event("SUCCESS"))).doesNotThrowAnyException();
    }
}
