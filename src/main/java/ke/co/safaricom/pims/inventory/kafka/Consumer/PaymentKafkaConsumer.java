package ke.co.safaricom.pims.inventory.kafka.Consumer;

/**
 * @author AOmar
 */
import ke.co.safaricom.pims.inventory.kafka.dto.PaymentSuccessEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentKafkaConsumer {


    @KafkaListener(
            topics = "${kafka.topic.payment-success}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onPaymentSuccess(PaymentSuccessEvent event) {
        log.info("Received payment event: eventId={}, orderId={}, amount={}",
                event.getEventId(), event.getOrderId(), event.getAmount());

        try {
            log.info("Successfully processed payment event for orderId={}", event.getOrderId());
        } catch (Exception ex) {
            log.error("Failed to process payment event for orderId={}: {}",
                    event.getOrderId(), ex.getMessage(), ex);
            throw ex;
        }
    }
}