package ke.co.safaricom.pims.inventory.kafka.Consumer;

import ke.co.safaricom.pims.inventory.kafka.dto.PaymentSuccessEvent;
import ke.co.safaricom.pims.inventory.service.OrderPaymentService;
import ke.co.safaricom.pims.inventory.service.PaymentDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentKafkaConsumer {

    private final OrderPaymentService orderPaymentService;

    @KafkaListener(
            topics = "${kafka.topic.payment-success}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onPaymentSuccess(PaymentSuccessEvent event) {
        try {
            if (!event.isSuccessful()) {
                log.info("Skipping payment event {} for order {}: status={}",
                        event.getEventId(), event.getOrderId(), event.getStatus());
                return;
            }
            PaymentDetails details = new PaymentDetails(
                    event.getPaymentMethod(),
                    event.getAmount().doubleValue(),
                    event.getTransactionRef(),
                    event.getPayerPhone(),
                    event.getPaidAt(),
                    null);
            orderPaymentService.recordPayment(event.getTenantId(), event.getOrderId(), details).block();
            log.info("Recorded payment from event {} for order {} (tenant {})",
                    event.getEventId(), event.getOrderId(), event.getTenantId());
        } catch (Exception ex) {
            log.error("Failed to process payment event {} for order {} (tenant {}): {}",
                    event.getEventId(), event.getOrderId(), event.getTenantId(), ex.getMessage(), ex);
        }
    }
}
