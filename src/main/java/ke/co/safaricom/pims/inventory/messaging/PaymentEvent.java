package ke.co.safaricom.pims.inventory.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Confirmed payment outcome published by the separate payment service once Daraja (M-Pesa)
 * confirms — or fails — an STK push. Inventory-service does not initiate or track the push
 * itself; this event is the first thing it sees of a given payment.
 *
 * @param eventId       dedup / log correlation key (Kafka delivery is at-least-once)
 * @param tenantId      routes the resulting ERPNext calls via {@code ErpNextTenantRouter} — there
 *                      is no JWT to resolve a tenant from on this path
 * @param orderId       the Sales Invoice {@code name} to record payment against
 * @param status        {@code "SUCCESS"} or {@code "FAILED"}; on failure the listener logs and
 *                      takes no ERPNext action
 * @param paymentMethod resolved against the tenant's live ERPNext "Mode of Payment" list by
 *                      {@code OrderPaymentService} (e.g. {@code "mpesa"} → {@code "M-Pesa"})
 * @param amount        the confirmed amount to allocate against the invoice
 * @param currency      informational; ERPNext invoices are posted in the tenant's base currency
 * @param transactionRef Daraja's transaction/receipt number — becomes the Payment Entry's
 *                       {@code reference_no} and the idempotency key for redelivered events
 * @param payerPhone    the phone number that paid; surfaced on the receipt / Payment Entry remarks
 * @param paidAt        becomes the Payment Entry's {@code reference_date}
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(
        String eventId,
        String tenantId,
        String orderId,
        String status,
        String paymentMethod,
        Double amount,
        String currency,
        String transactionRef,
        String payerPhone,
        String paidAt
) {
    public boolean isSuccessful() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}
