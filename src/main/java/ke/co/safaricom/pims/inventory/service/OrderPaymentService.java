package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import ke.co.safaricom.pims.inventory.web.model.SalesOrderSchemas;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records how a *submitted* POS order was paid for, as an ERPNext "Payment Entry". Shared by the
 * {@code POST /orders/{id}/pay} REST path (cash, or an already-confirmed M-Pesa payment) and the
 * Kafka listener that consumes confirmed M-Pesa payment events.
 *
 * <p>Payment recording necessarily comes <em>after</em> {@code SalesOrderService.submitOrder}, not
 * before: ERPNext's {@code Payment Entry.validate_reference_documents()} refuses to even insert a
 * draft Payment Entry that references a draft Sales Invoice ("{@code Sales Invoice X must be
 * submitted}"). So {@code /submit} finalises the invoice first ({@code docstatus: 0 → 1}), and
 * {@code /pay} then creates <em>and submits</em> the Payment Entry against it in one step — which
 * is what posts the GL entries and flips the invoice's status to "Paid".
 *
 * <p>Deliberately depends only on {@link ErpNextTenantRouter} (not on {@link SalesOrderService}),
 * since the dependency naturally runs the other way — {@code SalesOrderService} only ever submits
 * the invoice itself and never reaches into this service.
 */
@Service
public class OrderPaymentService {

    // ---- ERPNext doctypes, methods & defaults -------------------------------
    private static final String SI_DOCTYPE = "Sales Invoice";
    private static final String PE_DOCTYPE = "Payment Entry";
    private static final String MODE_OF_PAYMENT_DOCTYPE = "Mode of Payment";
    private static final String GET_PAYMENT_ENTRY_METHOD =
            "erpnext.accounts.doctype.payment_entry.payment_entry.get_payment_entry";
    private static final String STATUS_RECORDED = "recorded";

    /**
     * Preferred ERPNext "Mode of Payment" name per request-level payment method — tried first
     * (case-insensitively) before falling back to a normalised match against whatever modes are
     * actually configured for the tenant. Keeps the request-level vocabulary ({@code cash} /
     * {@code mpesa} — also the Kafka {@link ke.co.safaricom.pims.inventory.messaging.PaymentEvent}
     * contract) stable while letting the authoritative ERPNext-side name vary per tenant, instead
     * of requiring it to be kept in sync via static config.
     */
    private static final Map<String, String> PREFERRED_MODE_NAMES = Map.of("cash", "Cash", "mpesa", "M-Pesa");

    // ---- ERPNext field keys (avoids SonarLint S1192) ------------------------
    private static final String F_REFERENCE_NO     = "reference_no";
    private static final String F_REFERENCE_DATE   = "reference_date";
    private static final String F_MODE_OF_PAYMENT  = "mode_of_payment";
    private static final String F_PAID_AMOUNT      = "paid_amount";
    private static final String F_REMARKS          = "remarks";
    private static final String F_REFERENCES       = "references";
    private static final String F_ALLOCATED_AMOUNT = "allocated_amount";
    private static final String F_FILTERS          = "filters";

    private final ErpNextTenantRouter router;

    public OrderPaymentService(ErpNextTenantRouter router) {
        this.router = router;
    }

    /**
     * Records a payment against an already-submitted order: creates and submits a Payment Entry
     * referencing the Sales Invoice (which posts GL entries and flips its status to "Paid"), and
     * returns the amount paid plus any change due. Rejects orders that are still in draft —
     * {@code /submit} must run first. Idempotent on {@code details.transactionRef()} — a
     * redelivered event with the same reference returns the already-recorded entry rather than
     * creating a duplicate.
     */
    public Mono<SalesOrderSchemas.PaymentResponse> recordPayment(String tenantId, String orderId, PaymentDetails details) {
        return router.getOne(tenantId, SI_DOCTYPE, orderId, SINGLE_TYPE).flatMap(resp -> {
            ErpNextDoc order = resp.data();
            if (order.docstatus() == null || order.docstatus() != 1) {
                return Mono.error(new ConflictException(
                        "Order " + orderId + " must be submitted before payment can be recorded — "
                                + "ERPNext rejects a Payment Entry referencing a draft Sales Invoice"));
            }
            return findExistingPaymentEntry(tenantId, details.transactionRef())
                    .switchIfEmpty(Mono.defer(() -> createPaymentEntry(tenantId, order, details)))
                    .map(entry -> toPaymentResponse(orderId, order, details, entry));
        });
    }

    // ---- Idempotency guard ---------------------------------------------------

    private Mono<ErpNextDoc> findExistingPaymentEntry(String tenantId, String transactionRef) {
        if (!StringUtils.hasText(transactionRef)) {
            return Mono.empty();
        }
        Map<String, String> params = new HashMap<>();
        params.put(F_FILTERS, "[[\"" + F_REFERENCE_NO + "\",\"=\",\"" + transactionRef + "\"]]");
        return router.getList(tenantId, PE_DOCTYPE, params, LIST_TYPE)
                .flatMap(resp -> resp.data().isEmpty() ? Mono.empty() : Mono.just(resp.data().get(0)));
    }

    // ---- Payment Entry creation ----------------------------------------------

    /**
     * Builds the Payment Entry from ERPNext's own {@code get_payment_entry} draft rather than
     * from scratch — that draft is where ERPNext derives {@code paid_from}/{@code paid_to} (the
     * Debtors / mode-of-payment GL accounts) and {@code source_exchange_rate}/
     * {@code target_exchange_rate}; a hand-built body omitting them fails {@code validate()} with
     * "Target Exchange Rate is mandatory" since none of that derivation runs on a raw
     * {@code POST /api/resource/Payment Entry} insert (only the form's client-side JS does it).
     * This is exactly what clicking "Get Payment Entry" against the invoice does in the UI — and,
     * like that UI action, it requires the Sales Invoice to already be submitted: ERPNext's
     * {@code Payment Entry.validate_reference_documents()} rejects (with "{@code Sales Invoice X
     * must be submitted}") even a draft insert that references a draft invoice, so {@link
     * #recordPayment} only reaches here once {@code docstatus == 1}. The created entry is
     * immediately submitted too — that's what posts the GL entries and flips the invoice's status
     * to "Paid"; there is no later "finalise payment" step left for {@code /submit} to do.
     */
    private Mono<ErpNextDoc> createPaymentEntry(String tenantId, ErpNextDoc order, PaymentDetails details) {
        double grandTotal = orZero(order.grandTotal());
        double allocated = Math.min(details.amountTendered(), grandTotal);
        String referenceNo = StringUtils.hasText(details.transactionRef())
                ? details.transactionRef()
                : "POS-" + order.name() + "-" + System.currentTimeMillis();
        String referenceDate = StringUtils.hasText(details.paidAt()) ? details.paidAt() : LocalDate.now().toString();

        return Mono.zip(
                fetchPaymentEntryDraft(tenantId, order.name()),
                resolveModeOfPayment(tenantId, details.paymentMethod()))
                .flatMap(resolved -> {
                    Map<String, Object> body = new HashMap<>(resolved.getT1());
                    body.put(F_PAID_AMOUNT, allocated);
                    body.put("received_amount", allocated);
                    body.put(F_MODE_OF_PAYMENT, resolved.getT2());
                    body.put(F_REFERENCE_NO, referenceNo);
                    body.put(F_REFERENCE_DATE, referenceDate);
                    reallocate(body, allocated);
                    if (StringUtils.hasText(details.payerPhone())) {
                        body.put(F_REMARKS, paymentRemarks(details));
                    }
                    return router.create(tenantId, PE_DOCTYPE, body, SINGLE_TYPE)
                            .map(ErpNextSingleResponse::data)
                            .map(ErpNextDoc::name)
                            .flatMap(name -> submitPaymentEntry(tenantId, name));
                });
    }

    /** Fetches ERPNext's prefilled (unsaved) Payment Entry draft for the given Sales Invoice. */
    private Mono<Map<String, Object>> fetchPaymentEntryDraft(String tenantId, String orderId) {
        Map<String, Object> params = new HashMap<>();
        params.put("dt", SI_DOCTYPE);
        params.put("dn", orderId);
        return router.callMethod(tenantId, GET_PAYMENT_ENTRY_METHOD, params, DRAFT_TYPE)
                .map(ErpNextMessageResponse::message);
    }

    /** Overrides the allocation on the draft's (single) Sales Invoice reference row to the confirmed amount. */
    @SuppressWarnings("unchecked")
    private static void reallocate(Map<String, Object> body, double allocated) {
        Object refs = body.get(F_REFERENCES);
        if (refs instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Map<String, Object> reference = new HashMap<>((Map<String, Object>) first);
            reference.put(F_ALLOCATED_AMOUNT, allocated);
            body.put(F_REFERENCES, List.of(reference));
        }
    }

    private static String paymentRemarks(PaymentDetails details) {
        StringBuilder sb = new StringBuilder("Payer phone: ").append(details.payerPhone());
        if (StringUtils.hasText(details.notes())) sb.append(" | ").append(details.notes());
        return sb.toString();
    }

    // ---- Mode of Payment resolution ------------------------------------------

    /**
     * Resolves a request-level payment method ({@code cash} / {@code mpesa}) to the actual
     * ERPNext "Mode of Payment" name configured for this tenant — pulled live from ERPNext rather
     * than a static map, since tenants are free to name (or omit) these however they like.
     * Tries the {@link #PREFERRED_MODE_NAMES} hint first, then a punctuation/case-insensitive
     * match of the raw value, and fails clearly (listing what *is* available) rather than letting
     * ERPNext reject an unresolvable link with an opaque "Mode of Payment X not found".
     */
    private Mono<String> resolveModeOfPayment(String tenantId, String paymentMethod) {
        Map<String, String> params = new HashMap<>();
        params.put(F_FILTERS, "[[\"enabled\",\"=\",1]]");
        return router.getList(tenantId, MODE_OF_PAYMENT_DOCTYPE, params, LIST_TYPE)
                .flatMap(resp -> {
                    List<String> modes = resp.data().stream()
                            .map(ErpNextDoc::name)
                            .filter(StringUtils::hasText)
                            .toList();
                    String resolved = matchMode(modes, paymentMethod);
                    if (resolved == null) {
                        return Mono.error(new ServiceValidationException(
                                "Payment method '" + paymentMethod + "' has no matching ERPNext Mode of Payment"
                                        + " for this tenant — available: " + String.join(", ", modes)));
                    }
                    return Mono.just(resolved);
                });
    }

    private static String matchMode(List<String> modes, String paymentMethod) {
        String key = paymentMethod == null ? "" : paymentMethod.toLowerCase(Locale.ROOT);
        String preferred = PREFERRED_MODE_NAMES.get(key);
        String normalisedRequest = normalise(paymentMethod);
        for (String mode : modes) {
            if ((preferred != null && mode.equalsIgnoreCase(preferred)) || normalise(mode).equals(normalisedRequest)) {
                return mode;
            }
        }
        return null;
    }

    /** Lowercases and strips punctuation/whitespace so e.g. "mpesa" matches "M-Pesa" or "M Pesa". */
    private static String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ---- Submission (chained immediately after creation in createPaymentEntry) ----

    /**
     * Re-fetches the freshly-created draft Payment Entry <em>in full</em> and submits it via
     * {@code frappe.client.submit} — this is what posts GL entries and flips the Sales Invoice's
     * status to "Paid".
     *
     * <p>The submit RPC reconstructs its working document purely from the {@code "doc"} payload
     * it's handed — {@code frappe.get_doc(dict)} populates an in-memory doc straight from the
     * dict's own keys, with no DB load — so echoing back only {@code {doctype, name, modified,
     * docstatus}} leaves required fields (e.g. {@code paid_from}/{@code paid_to}/{@code
     * references}) empty and fails {@code validate()}. Fetching the complete current document
     * immediately beforehand both supplies everything {@code validate()} needs <em>and</em>
     * naturally satisfies {@code check_if_latest()}'s optimistic-lock comparison on {@code
     * modified} — exactly what the desk UI does when you click Submit (it posts back its
     * locally-cached copy of the loaded document).
     */
    private Mono<ErpNextDoc> submitPaymentEntry(String tenantId, String name) {
        return router.getOne(tenantId, PE_DOCTYPE, name, RAW_SINGLE_TYPE)
                .map(ErpNextSingleResponse::data)
                .flatMap(latest -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("doc", latest);
                    return router.callMethod(tenantId, "frappe.client.submit", body, SUBMIT_TYPE);
                })
                .map(ErpNextMessageResponse::message);
    }

    // ---- Mapping --------------------------------------------------------------

    private SalesOrderSchemas.PaymentResponse toPaymentResponse(
            String orderId, ErpNextDoc order, PaymentDetails details, ErpNextDoc entry) {
        double grandTotal = orZero(order.grandTotal());
        double amountPaid = details.amountTendered();
        double changeDue = Math.max(0, amountPaid - grandTotal);
        return new SalesOrderSchemas.PaymentResponse(
                orderId,
                STATUS_RECORDED,
                details.paymentMethod(),
                amountPaid,
                changeDue,
                entry.referenceNo(),
                entry.name());
    }

    private static double orZero(Double value) {
        return value != null ? value : 0;
    }

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<Map<String, Object>>> RAW_SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextMessageResponse<Map<String, Object>>> DRAFT_TYPE =
            new ParameterizedTypeReference<>() {};

    // frappe.client.submit is an /api/method/* RPC — Frappe wraps its return value in
    // {"message": ...}, not the {"data": ...} envelope used by /api/resource/* endpoints.
    private static final ParameterizedTypeReference<ErpNextMessageResponse<ErpNextDoc>> SUBMIT_TYPE =
            new ParameterizedTypeReference<>() {};
}
