package ke.co.safaricom.pims.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    private static final String TENANT = "test-tenant";
    private static final String ORDER_ID = "SINV-2024-00001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    @Mock
    private ErpNextTenantRouter router;

    private ReceiptService service;

    @BeforeEach
    void setUp() {
        service = new ReceiptService(router);
    }

    // ---- helpers ------------------------------------------------------------

    private static ErpNextDoc doc(Map<String, Object> fields) {
        return MAPPER.convertValue(fields, ErpNextDoc.class);
    }

    private static ErpNextDoc orderDoc(int docstatus) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", ORDER_ID);
        fields.put("docstatus", docstatus);
        fields.put("customer", "Jane Doe");
        fields.put("currency", "KES");
        fields.put("posting_date", "2026-06-06");
        fields.put("net_total", 900.0);
        fields.put("total_taxes_and_charges", 100.0);
        fields.put("grand_total", 1000.0);
        fields.put("items", List.of(Map.of(
                "item_name", "Amoxicillin 500mg",
                "qty", 2.0,
                "rate", 450.0,
                "amount", 900.0)));
        return doc(fields);
    }

    private static ErpNextDoc paymentEntryDoc(String modeOfPayment, double paidAmount, String referenceNo) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", "PE-0001");
        fields.put("docstatus", 1);
        fields.put("mode_of_payment", modeOfPayment);
        fields.put("paid_amount", paidAmount);
        fields.put("reference_no", referenceNo);
        fields.put("reference_date", "2026-06-06");
        return doc(fields);
    }

    private static boolean looksLikePdf(byte[] pdf) {
        return pdf.length > 5 && new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
    }

    // ---- generateReceipt ------------------------------------------------------

    @Test
    void generateReceipt_rejects_draft_orders() {
        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(orderDoc(0))));

        StepVerifier.create(service.generateReceipt(TENANT, ORDER_ID, "Dr. Jane Mwangi"))
                .expectError(ServiceValidationException.class)
                .verify();

        verify(router, never()).getList(eq(TENANT), eq("Payment Entry"), anyMap(), eq(LIST_TYPE));
    }

    @Test
    void generateReceipt_renders_pdf_for_submitted_order_with_payment() {
        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(orderDoc(1))));
        when(router.getList(eq(TENANT), eq("Payment Entry"), anyMap(), eq(LIST_TYPE)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(paymentEntryDoc("Cash", 1000.0, "POS-SINV-2024-00001-1")))));

        StepVerifier.create(service.generateReceipt(TENANT, ORDER_ID, "Dr. Jane Mwangi"))
                .assertNext(pdf -> {
                    assertThat(pdf).isNotEmpty();
                    assertThat(looksLikePdf(pdf)).as("should start with the %%PDF- magic bytes").isTrue();
                })
                .verifyComplete();
    }

    @Test
    void generateReceipt_renders_pdf_for_submitted_order_without_payment_entry() {
        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(orderDoc(1))));
        when(router.getList(eq(TENANT), eq("Payment Entry"), anyMap(), eq(LIST_TYPE)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of())));

        StepVerifier.create(service.generateReceipt(TENANT, ORDER_ID, "Dr. Jane Mwangi"))
                .assertNext(pdf -> {
                    assertThat(pdf).isNotEmpty();
                    assertThat(looksLikePdf(pdf)).as("should start with the %%PDF- magic bytes").isTrue();
                })
                .verifyComplete();
    }
}
