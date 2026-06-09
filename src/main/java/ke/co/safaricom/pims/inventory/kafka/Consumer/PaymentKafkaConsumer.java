package ke.co.safaricom.pims.inventory.kafka.Consumer;

import ke.co.safaricom.pims.inventory.config.ErpNextProperties;
import ke.co.safaricom.pims.inventory.kafka.dto.PaymentSuccessEvent;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.OrderPaymentService;
import ke.co.safaricom.pims.inventory.service.PaymentDetails;
import ke.co.safaricom.pims.inventory.service.SalesOrderService;
import ke.co.safaricom.pims.inventory.web.model.SalesOrderSchemas;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentKafkaConsumer {

    private final SalesOrderService salesOrderService;
    private final OrderPaymentService orderPaymentService;
    private final ErpNextProperties erpNextProperties;

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
            if (!StringUtils.hasText(event.getOrderId())) {
                log.error("Skipping payment event {}: missing order_id/orderId", event.getEventId());
                return;
            }
            if (event.getAmount() == null) {
                log.error("Skipping payment event {} for order {}: missing amount",
                        event.getEventId(), event.getOrderId());
                return;
            }

            String tenantId = resolveTenantId(event);
            PaymentDetails details = new PaymentDetails(
                    normalizePaymentMethod(event.getPaymentMethod()),
                    event.getAmount().doubleValue(),
                    event.getTransactionRef(),
                    event.getPayerPhone(),
                    normalizePaidAt(event.getPaidAt()),
                    null);
            SalesOrderSchemas.SubmitOrderRequest submitReq = new SalesOrderSchemas.SubmitOrderRequest(
                    details.paymentMethod(),
                    details.amountTendered(),
                    event.getPayerPhone(),
                    null);
            salesOrderService.ensureSubmitted(tenantId, event.getOrderId(), submitReq)
                    .then(Mono.defer(() -> orderPaymentService.recordPayment(
                            tenantId, event.getOrderId(), details)))
                    .block();
            log.info("Recorded payment from event {} for order {} (tenant {})",
                    event.getEventId(), event.getOrderId(), tenantId);
        } catch (Exception ex) {
            log.error("Failed to process payment event {} for order {} (tenant {}): {}",
                    event.getEventId(), event.getOrderId(), event.getTenantId(), ex.getMessage(), ex);
        }
    }

    /**
     * Payment service currently persists the header <em>name</em> ({@code X-Tenant-Id}) instead of
     * the header value — treat that as missing and fall back to the configured wrapper tenant when set.
     */
    private String resolveTenantId(PaymentSuccessEvent event) {
        String tenantId = event.getTenantId();
        if (StringUtils.hasText(tenantId) && !TenantContextResolver.TENANT_HEADER.equals(tenantId)) {
            return tenantId;
        }
        ErpNextProperties.WrapperConfig wrapper = erpNextProperties.wrapper();
        if (wrapper != null && StringUtils.hasText(wrapper.tenantId())) {
            log.warn("Payment event tenant_id={} is not a real tenant; using erpnext.wrapper.tenant-id={}",
                    tenantId, wrapper.tenantId());
            return wrapper.tenantId();
        }
        if (StringUtils.hasText(tenantId)) {
            return tenantId;
        }
        throw new IllegalArgumentException("Payment event missing tenant_id");
    }


    /**
     * ERPNext {@code reference_date} accepts date-only ({@code yyyy-MM-dd}). Payment service sends
     * {@link java.time.LocalDateTime#toString()} values ({@code 2026-06-07T18:25:13} or with fractional seconds / {@code Z}).
     */
    static String normalizePaidAt(String paidAt) {
        if (!StringUtils.hasText(paidAt)) {
            return null;
        }
        String trimmed = paidAt.trim();
        int tIndex = trimmed.indexOf('T');
        if (tIndex > 0) {
            return trimmed.substring(0, tIndex);
        }
        if (trimmed.length() >= 10) {
            return trimmed.substring(0, 10);
        }
        return trimmed;
    }

    /** Normalises payment-service values such as {@code MPESA} / {@code CASH} to {@code mpesa} / {@code cash}. */
    static String normalizePaymentMethod(String paymentMethod) {
        if (!StringUtils.hasText(paymentMethod)) {
            return paymentMethod;
        }
        return paymentMethod.toLowerCase(Locale.ROOT);
    }
}
