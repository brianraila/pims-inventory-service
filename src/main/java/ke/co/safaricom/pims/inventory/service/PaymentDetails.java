package ke.co.safaricom.pims.inventory.service;

/** Normalised payment details shared by the REST {@code /pay} path and the Kafka payment-event listener. */
public record PaymentDetails(
        String paymentMethod,
        Double amountTendered,
        String transactionRef,
        String payerPhone,
        String paidAt,
        String notes
) {}
