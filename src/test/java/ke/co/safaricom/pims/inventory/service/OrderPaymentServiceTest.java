package ke.co.safaricom.pims.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderPaymentServiceTest {

    private static final String TENANT = "test-tenant";
    private static final String ORDER_ID = "SINV-2024-00001";
    private static final String GET_PAYMENT_ENTRY_METHOD =
            "erpnext.accounts.doctype.payment_entry.payment_entry.get_payment_entry";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextSingleResponse<Map<String, Object>>> RAW_SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextMessageResponse<Map<String, Object>>> DRAFT_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextMessageResponse<ErpNextDoc>> SUBMIT_TYPE =
            new ParameterizedTypeReference<>() {};

    @Mock
    private ErpNextTenantRouter router;

    private OrderPaymentService service;

    @BeforeEach
    void setUp() {
        service = new OrderPaymentService(router);
    }

    // ---- helpers ------------------------------------------------------------

    private static ErpNextDoc doc(Map<String, Object> fields) {
        return MAPPER.convertValue(fields, ErpNextDoc.class);
    }

    private static ErpNextDoc orderDoc(int docstatus, double grandTotal) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", ORDER_ID);
        fields.put("docstatus", docstatus);
        fields.put("customer", "Jane Doe");
        fields.put("grand_total", grandTotal);
        return doc(fields);
    }

    private static ErpNextDoc paymentEntryDoc(String name, String referenceNo, int docstatus) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("reference_no", referenceNo);
        fields.put("docstatus", docstatus);
        return doc(fields);
    }

    /** Mirrors the full document ERPNext returns when the freshly-created draft is re-fetched raw, immediately before submission. */
    private static Map<String, Object> rawPaymentEntry(String name, String referenceNo) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("doctype", "Payment Entry");
        raw.put("name", name);
        raw.put("payment_type", "Receive");
        raw.put("paid_from", "Debtors - S");
        raw.put("paid_to", "Cash - S");
        raw.put("reference_no", referenceNo);
        raw.put("modified", "2026-06-06 10:00:00.000000");
        raw.put("docstatus", 0);
        return raw;
    }

    private static ErpNextDoc modeOfPaymentDoc(String name) {
        return doc(Map.of("name", name));
    }

    /** Mirrors the shape ERPNext's {@code get_payment_entry} RPC returns — a fully prefilled, ready-to-insert draft. */
    private static Map<String, Object> prefilledDraft(double allocated) {
        Map<String, Object> reference = new HashMap<>();
        reference.put("reference_doctype", "Sales Invoice");
        reference.put("reference_name", ORDER_ID);
        reference.put("total_amount", allocated);
        reference.put("outstanding_amount", allocated);
        reference.put("allocated_amount", allocated);

        Map<String, Object> draft = new HashMap<>();
        draft.put("doctype", "Payment Entry");
        draft.put("payment_type", "Receive");
        draft.put("party_type", "Customer");
        draft.put("party", "Jane Doe");
        draft.put("paid_from", "Debtors - S");
        draft.put("paid_to", "Cash - S");
        draft.put("paid_from_account_currency", "KES");
        draft.put("paid_to_account_currency", "KES");
        draft.put("source_exchange_rate", 1.0);
        draft.put("target_exchange_rate", 1.0);
        draft.put("paid_amount", allocated);
        draft.put("received_amount", allocated);
        draft.put("references", List.of(reference));
        return draft;
    }

    private void stubPaymentEntryDraft(double allocated) {
        when(router.callMethod(eq(TENANT), eq(GET_PAYMENT_ENTRY_METHOD), anyMap(), eq(DRAFT_TYPE)))
                .thenReturn(Mono.just(new ErpNextMessageResponse<>(prefilledDraft(allocated))));
    }

    private void stubModesOfPayment(String... names) {
        List<ErpNextDoc> modes = List.of(names).stream().map(OrderPaymentServiceTest::modeOfPaymentDoc).toList();
        when(router.getList(eq(TENANT), eq("Mode of Payment"), anyMap(), eq(LIST_TYPE)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(modes)));
    }

    /** Stubs the idempotency guard's lookup (triggered whenever {@code transactionRef} is present) to find nothing. */
    private void stubNoExistingPaymentEntry() {
        when(router.getList(eq(TENANT), eq("Payment Entry"), anyMap(), eq(LIST_TYPE)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of())));
    }

    /**
     * Stubs the immediate post-create submission of the Payment Entry — re-fetching the freshly
     * created draft in full (frappe.client.submit needs the complete document, not a hand-picked
     * subset of fields, since frappe.get_doc on a dict has no DB load) and submitting it, which
     * is what posts GL entries and flips the invoice to "Paid".
     */
    private void stubSubmitPaymentEntry(String name, String referenceNo) {
        when(router.getOne(eq(TENANT), eq("Payment Entry"), eq(name), eq(RAW_SINGLE_TYPE)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(rawPaymentEntry(name, referenceNo))));
        when(router.callMethod(eq(TENANT), eq("frappe.client.submit"), anyMap(), eq(SUBMIT_TYPE)))
                .thenReturn(Mono.just(new ErpNextMessageResponse<>(paymentEntryDoc(name, referenceNo, 1))));
    }

    // ---- recordPayment -------------------------------------------------------

    @Test
    void recordPayment_cash_creates_and_submits_payment_entry_and_computes_change_due() {
        ErpNextDoc order = orderDoc(1, 1000.0);
        ErpNextDoc createdEntry = paymentEntryDoc("PE-0001", "POS-SINV-2024-00001-1", 0);

        when(router.getOne(eq(TENANT), eq("Sales Invoice"), eq(ORDER_ID), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(order)));
        stubPaymentEntryDraft(1000.0);
        stubModesOfPayment("Cheque", "Cash", "Credit Card");

        AtomicReference<Map<String, Object>> capturedBody = new AtomicReference<>();
        when(router.create(eq(TENANT), eq("Payment Entry"), anyMap(), eq(SINGLE_TYPE)))
                .thenAnswer(inv -> {
                    capturedBody.set(inv.getArgument(2));
                    return Mono.just(new ErpNextSingleResponse<>(createdEntry));
                });

        Map<String, Object> rawCreatedEntry = rawPaymentEntry("PE-0001", "POS-SINV-2024-00001-1");
        when(router.getOne(TENANT, "Payment Entry", "PE-0001", RAW_SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(rawCreatedEntry)));

        AtomicReference<Map<String, Object>> capturedSubmitBody = new AtomicReference<>();
        when(router.callMethod(eq(TENANT), eq("frappe.client.submit"), anyMap(), eq(SUBMIT_TYPE)))
                .thenAnswer(inv -> {
                    capturedSubmitBody.set(inv.getArgument(2));
                    return Mono.just(new ErpNextMessageResponse<>(paymentEntryDoc("PE-0001", "POS-SINV-2024-00001-1", 1)));
                });

        PaymentDetails details = new PaymentDetails("cash", 1500.0, null, null, null, null);

        StepVerifier.create(service.recordPayment(TENANT, ORDER_ID, details))
                .assertNext(resp -> {
                    assertThat(resp.orderId()).isEqualTo(ORDER_ID);
                    assertThat(resp.status()).isEqualTo("recorded");
                    assertThat(resp.paymentMethod()).isEqualTo("cash");
                    assertThat(resp.amountPaid()).isEqualTo(1500.0);
                    assertThat(resp.changeDue()).isEqualTo(500.0);
                    assertThat(resp.paymentEntryId()).isEqualTo("PE-0001");
                })
                .verifyComplete();

        assertThat(capturedBody.get())
                .containsEntry("payment_type", "Receive")
                .containsEntry("party_type", "Customer")
                .containsEntry("party", "Jane Doe")
                .containsEntry("mode_of_payment", "Cash")
                .containsEntry("paid_amount", 1000.0)      // capped at the invoice's grand total
                .containsEntry("received_amount", 1000.0)
                // carried over verbatim from ERPNext's prefilled draft — the crux of the exchange-rate fix
                .containsEntry("paid_from", "Debtors - S")
                .containsEntry("paid_to", "Cash - S")
                .containsEntry("source_exchange_rate", 1.0)
                .containsEntry("target_exchange_rate", 1.0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> references = (List<Map<String, Object>>) capturedBody.get().get("references");
        assertThat(references).hasSize(1);
        assertThat(references.get(0)).containsEntry("allocated_amount", 1000.0);

        // the freshly-created draft Payment Entry is re-fetched in full and submitted immediately
        // — that's what posts GL entries and flips the Sales Invoice's status to "Paid". The
        // submit RPC reconstructs its working doc purely from the "doc" payload (frappe.get_doc
        // on a dict has no DB load), so it must be the COMPLETE document, not a hand-picked
        // subset — otherwise required fields (paid_from/paid_to/references, ...) end up empty
        // and validate() fails.
        @SuppressWarnings("unchecked")
        Map<String, Object> submittedDoc = (Map<String, Object>) capturedSubmitBody.get().get("doc");
        assertThat(submittedDoc).isEqualTo(rawCreatedEntry);
    }

    @Test
    void recordPayment_resolves_mode_of_payment_case_and_punctuation_insensitively() {
        ErpNextDoc order = orderDoc(1, 1000.0);
        ErpNextDoc createdEntry = paymentEntryDoc("PE-0002", "QGR7XXXXX1", 0);

        when(router.getOne(eq(TENANT), eq("Sales Invoice"), eq(ORDER_ID), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(order)));
        stubNoExistingPaymentEntry();
        stubPaymentEntryDraft(1000.0);
        // tenant has no canonically-spelled "M-Pesa" — only a loose variant; should still resolve to it
        stubModesOfPayment("Cash", "M PESA");

        AtomicReference<Map<String, Object>> capturedBody = new AtomicReference<>();
        when(router.create(eq(TENANT), eq("Payment Entry"), anyMap(), eq(SINGLE_TYPE)))
                .thenAnswer(inv -> {
                    capturedBody.set(inv.getArgument(2));
                    return Mono.just(new ErpNextSingleResponse<>(createdEntry));
                });
        stubSubmitPaymentEntry("PE-0002", "QGR7XXXXX1");

        PaymentDetails details = new PaymentDetails("mpesa", 1000.0, "QGR7XXXXX1", "254712345678", null, null);

        StepVerifier.create(service.recordPayment(TENANT, ORDER_ID, details))
                .assertNext(resp -> assertThat(resp.paymentEntryId()).isEqualTo("PE-0002"))
                .verifyComplete();

        assertThat(capturedBody.get()).containsEntry("mode_of_payment", "M PESA");
    }

    @Test
    void recordPayment_fails_clearly_when_no_mode_of_payment_matches() {
        ErpNextDoc order = orderDoc(1, 1000.0);

        when(router.getOne(eq(TENANT), eq("Sales Invoice"), eq(ORDER_ID), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(order)));
        stubNoExistingPaymentEntry();
        stubPaymentEntryDraft(1000.0);
        stubModesOfPayment("Cheque", "Cash", "Credit Card", "Wire Transfer", "Bank Draft"); // no M-Pesa on this tenant

        PaymentDetails details = new PaymentDetails("mpesa", 1000.0, "QGR7XXXXX1", "254712345678", null, null);

        StepVerifier.create(service.recordPayment(TENANT, ORDER_ID, details))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(ServiceValidationException.class);
                    assertThat(ex.getMessage())
                            .contains("mpesa")
                            .contains("Cheque, Cash, Credit Card, Wire Transfer, Bank Draft");
                })
                .verify();

        verify(router, never()).create(eq(TENANT), eq("Payment Entry"), anyMap(), eq(SINGLE_TYPE));
    }

    @Test
    void recordPayment_redelivered_event_short_circuits_on_existing_transaction_ref() {
        ErpNextDoc order = orderDoc(1, 1000.0);
        ErpNextDoc existingEntry = paymentEntryDoc("PE-EXIST", "QGR7XXXXX1", 0);

        when(router.getOne(eq(TENANT), eq("Sales Invoice"), eq(ORDER_ID), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(order)));
        when(router.getList(eq(TENANT), eq("Payment Entry"), anyMap(), eq(LIST_TYPE)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(existingEntry))));

        PaymentDetails details = new PaymentDetails("mpesa", 1000.0, "QGR7XXXXX1", "254712345678", null, null);

        StepVerifier.create(service.recordPayment(TENANT, ORDER_ID, details))
                .assertNext(resp -> {
                    assertThat(resp.paymentEntryId()).isEqualTo("PE-EXIST");
                    assertThat(resp.transactionRef()).isEqualTo("QGR7XXXXX1");
                })
                .verifyComplete();

        verify(router, never()).create(eq(TENANT), eq("Payment Entry"), anyMap(), eq(SINGLE_TYPE));
    }

    @Test
    void recordPayment_rejects_when_order_still_draft() {
        ErpNextDoc draftOrder = orderDoc(0, 1000.0);
        when(router.getOne(eq(TENANT), eq("Sales Invoice"), eq(ORDER_ID), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(draftOrder)));

        PaymentDetails details = new PaymentDetails("cash", 1000.0, null, null, null, null);

        StepVerifier.create(service.recordPayment(TENANT, ORDER_ID, details))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(ConflictException.class);
                    assertThat(ex.getMessage()).contains("must be submitted");
                })
                .verify();

        verify(router, never()).create(eq(TENANT), eq("Payment Entry"), anyMap(), eq(SINGLE_TYPE));
    }
}
