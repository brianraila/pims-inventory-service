package ke.co.safaricom.pims.inventory.messaging;

import ke.co.safaricom.pims.inventory.service.OrderPaymentService;
import ke.co.safaricom.pims.inventory.service.PaymentDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes confirmed M-Pesa payment events from the separate payment service and feeds them
 * through the same {@code recordPayment} path that {@code POST /orders/{id}/pay} uses for cash —
 * i.e. it "performs the /pay" on the order's behalf once Daraja has confirmed the transaction.
 *
 * Runs on its own consumer thread (not a Netty event-loop thread), so blocking on the resulting
 * {@code Mono} processes one record at a time without starving the reactive pipeline elsewhere.
 * Errors are caught per-record so a single bad message can't wedge the consumer; redelivery
 * safety is handled by {@link OrderPaymentService#recordPayment}'s idempotency guard on
 * {@code transaction_ref}.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final OrderPaymentService orderPaymentService;

    public PaymentEventListener(OrderPaymentService orderPaymentService) {
        this.orderPaymentService = orderPaymentService;
    }

    @KafkaListener(topics = "${payments.kafka.topic}")
    public void onPaymentEvent(PaymentEvent event) {
        try {
            handle(event);
        } catch (Exception ex) {
            log.error("Failed to process payment event {} for order {} (tenant {}): {}",
                    event.eventId(), event.orderId(), event.tenantId(), ex.getMessage(), ex);
        }
    }

    private void handle(PaymentEvent event) {
        if (!event.isSuccessful()) {
            log.info("Skipping payment event {} for order {}: status={}", event.eventId(), event.orderId(), event.status());
            return;
        }
        PaymentDetails details = new PaymentDetails(
                event.paymentMethod(), event.amount(), event.transactionRef(), event.payerPhone(), event.paidAt(), null);
        orderPaymentService.recordPayment(event.tenantId(), event.orderId(), details).block();
        log.info("Recorded payment from event {} for order {} (tenant {})", event.eventId(), event.orderId(), event.tenantId());
    }
}
