package ke.co.safaricom.pims.inventory.kafka.Consumer;

import ke.co.safaricom.pims.inventory.config.ErpNextProperties;
import ke.co.safaricom.pims.inventory.kafka.dto.PaymentSuccessEvent;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.OrderPaymentService;
import ke.co.safaricom.pims.inventory.service.PaymentDetails;
import ke.co.safaricom.pims.inventory.service.SalesOrderService;
import ke.co.safaricom.pims.inventory.web.model.SalesOrderSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentKafkaConsumerTest {

    private static final String TENANT = "abc-pharmacy-westlands";
    private static final String ORDER_ID = "SINV-2024-00001";

    @Mock
    private SalesOrderService salesOrderService;

    @Mock
    private OrderPaymentService orderPaymentService;

    @Mock
    private ErpNextProperties erpNextProperties;

    private PaymentKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentKafkaConsumer(salesOrderService, orderPaymentService, erpNextProperties);
    }

    private void stubEnsureSubmittedSuccess() {
        when(salesOrderService.ensureSubmitted(anyString(), anyString(), any()))
                .thenReturn(Mono.empty());
    }

    private static PaymentSuccessEvent event(String status) {
        return event(status, TENANT, "mpesa");
    }

    private static PaymentSuccessEvent event(String status, String tenantId, String paymentMethod) {
        return PaymentSuccessEvent.builder()
                .eventId("evt-1")
                .tenantId(tenantId)
                .orderId(ORDER_ID)
                .status(status)
                .paymentMethod(paymentMethod)
                .amount(BigDecimal.valueOf(1500.0))
                .currency("KES")
                .transactionRef("QGR7XXXXX1")
                .payerPhone("254712345678")
                .paidAt("2026-06-06T10:15:30Z")
                .build();
    }

    @Test
    void onPaymentSuccess_records_payment_for_successful_event() {
        stubEnsureSubmittedSuccess();
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
                        && "2026-06-06".equals(details.paidAt())));
    }

    @Test
    void onPaymentSuccess_normalises_payment_service_MPESA_method() {
        stubEnsureSubmittedSuccess();
        when(orderPaymentService.recordPayment(eq(TENANT), eq(ORDER_ID), any(PaymentDetails.class)))
                .thenReturn(Mono.just(new SalesOrderSchemas.PaymentResponse(
                        ORDER_ID, "recorded", "mpesa", 1500.0, 0.0, "QGR7XXXXX1", "PE-0001")));

        consumer.onPaymentSuccess(event("SUCCESS", TENANT, "MPESA"));

        verify(orderPaymentService).recordPayment(eq(TENANT), eq(ORDER_ID),
                argThat(details -> "mpesa".equals(details.paymentMethod())));
    }

    @Test
    void onPaymentSuccess_uses_wrapper_tenant_when_event_carries_header_name() {
        stubEnsureSubmittedSuccess();
        when(erpNextProperties.wrapper()).thenReturn(new ErpNextProperties.WrapperConfig("http://wrapper", TENANT));
        when(orderPaymentService.recordPayment(eq(TENANT), eq(ORDER_ID), any(PaymentDetails.class)))
                .thenReturn(Mono.just(new SalesOrderSchemas.PaymentResponse(
                        ORDER_ID, "recorded", "cash", 1500.0, 0.0, "QGR7XXXXX1", "PE-0001")));

        consumer.onPaymentSuccess(event("SUCCESS", TenantContextResolver.TENANT_HEADER, "CASH"));

        verify(orderPaymentService).recordPayment(eq(TENANT), eq(ORDER_ID), any(PaymentDetails.class));
    }

    @Test
    void onPaymentSuccess_skips_failed_events_without_recording_payment() {
        consumer.onPaymentSuccess(event("FAILED"));

        verify(orderPaymentService, never()).recordPayment(any(), any(), any());
    }

    @Test
    void onPaymentSuccess_skips_when_order_id_missing() {
        PaymentSuccessEvent missingOrder = PaymentSuccessEvent.builder()
                .eventId("evt-1")
                .status("SUCCESS")
                .amount(BigDecimal.TEN)
                .build();

        consumer.onPaymentSuccess(missingOrder);

        verify(orderPaymentService, never()).recordPayment(any(), any(), any());
    }

    @Test
    void onPaymentSuccess_ensures_submitted_before_recording_payment() {
        stubEnsureSubmittedSuccess();
        when(orderPaymentService.recordPayment(eq(TENANT), eq(ORDER_ID), any(PaymentDetails.class)))
                .thenReturn(Mono.just(new SalesOrderSchemas.PaymentResponse(
                        ORDER_ID, "recorded", "mpesa", 1500.0, 0.0, "QGR7XXXXX1", "PE-0001")));

        consumer.onPaymentSuccess(event("SUCCESS"));

        InOrder order = inOrder(salesOrderService, orderPaymentService);
        order.verify(salesOrderService).ensureSubmitted(eq(TENANT), eq(ORDER_ID), argThat(req ->
                "mpesa".equals(req.paymentMethod())
                        && req.amountReceived() == 1500.0
                        && "254712345678".equals(req.mpesaPhone())));
        order.verify(orderPaymentService).recordPayment(eq(TENANT), eq(ORDER_ID), any(PaymentDetails.class));
    }

    @Test
    void onPaymentSuccess_swallows_ensure_submitted_failures_so_consumer_keeps_running() {
        when(salesOrderService.ensureSubmitted(eq(TENANT), eq(ORDER_ID), any()))
                .thenReturn(Mono.error(new RuntimeException("ERPNext unavailable")));

        assertThatCode(() -> consumer.onPaymentSuccess(event("SUCCESS"))).doesNotThrowAnyException();

        verify(orderPaymentService, never()).recordPayment(any(), any(), any());
    }

    @Test
    void onPaymentSuccess_swallows_exceptions_so_consumer_keeps_running() {
        stubEnsureSubmittedSuccess();
        when(orderPaymentService.recordPayment(eq(TENANT), eq(ORDER_ID), any(PaymentDetails.class)))
                .thenReturn(Mono.error(new RuntimeException("ERPNext unavailable")));

        assertThatCode(() -> consumer.onPaymentSuccess(event("SUCCESS"))).doesNotThrowAnyException();
    }

    @Test
    void normalizePaidAt_strips_iso_datetime_to_date_only() {
        assertThat(PaymentKafkaConsumer.normalizePaidAt(null)).isNull();
        assertThat(PaymentKafkaConsumer.normalizePaidAt("")).isNull();
        assertThat(PaymentKafkaConsumer.normalizePaidAt("   ")).isNull();
        assertThat(PaymentKafkaConsumer.normalizePaidAt("2026-06-07")).isEqualTo("2026-06-07");
        assertThat(PaymentKafkaConsumer.normalizePaidAt("2026-06-07T18:25:13")).isEqualTo("2026-06-07");
        assertThat(PaymentKafkaConsumer.normalizePaidAt("2026-06-07T18:25:13.316")).isEqualTo("2026-06-07");
        assertThat(PaymentKafkaConsumer.normalizePaidAt("2026-06-06T10:15:30Z")).isEqualTo("2026-06-06");
    }

    @Test
    void normalizePaymentMethod_lowercases_payment_service_values() {
        assertThat(PaymentKafkaConsumer.normalizePaymentMethod("MPESA")).isEqualTo("mpesa");
        assertThat(PaymentKafkaConsumer.normalizePaymentMethod("CASH")).isEqualTo("cash");
    }
}
