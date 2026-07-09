package ke.co.safaricom.pims.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesInvoiceReportSupportTest {

    private static final String TENANT = "abc-pharmacy-westlands";
    private static final String INVOICE = "ACC-SINV-2026-00144";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ErpNextTenantRouter router;

    private SalesInvoiceReportSupport support;

    @BeforeEach
    void setUp() {
        support = new SalesInvoiceReportSupport(router);
    }

    @Test
    void fetchPaymentEntriesForInvoices_doesNotRequestCurrencyField() {
        when(router.getList(eq(TENANT), eq("Payment Entry"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of())));

        StepVerifier.create(support.fetchPaymentEntriesForInvoices(TENANT, List.of(INVOICE)))
                .assertNext(rows -> assertThat(rows).isEmpty())
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(router).getList(eq(TENANT), eq("Payment Entry"), paramsCaptor.capture(), any(ParameterizedTypeReference.class));
        assertThat(paramsCaptor.getValue().get("fields")).doesNotContain("currency");
        assertThat(paramsCaptor.getValue().get("fields"))
                .isEqualTo("[\"name\",\"mode_of_payment\",\"paid_amount\",\"posting_date\",\"docstatus\"]");
    }

    @Test
    void fetchInvoiceItems_fallsBackToInvoiceGetOneWhenListRowsLackItemCode() throws Exception {
        Map<String, Object> nameOnlyRow = Map.of("name", "line-1");
        when(router.getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(nameOnlyRow))));
        when(router.callMethod(
                        eq(TENANT),
                        eq("pims.api.reports.list_sales_invoice_items"),
                        anyMap(),
                        any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.error(new RuntimeException("report API unavailable")));

        ErpNextDoc invoice = invoice(Map.of(
                "name", INVOICE,
                "items", List.of(Map.of(
                        "item_code", "PIMS-BA3027E2",
                        "item_name", "Acyclovir 400mg Tablets",
                        "qty", 1.0,
                        "amount", 50.0,
                        "parent", INVOICE,
                        "rate", 50.0))));
        when(router.getOne(eq(TENANT), eq("Sales Invoice"), eq(INVOICE), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(invoice)));

        StepVerifier.create(support.fetchInvoiceItems(TENANT, List.of(INVOICE)))
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).get("item_code")).isEqualTo("PIMS-BA3027E2");
                    assertThat(rows.get(0).get("amount")).isEqualTo(50.0);
                })
                .verifyComplete();
    }

    @Test
    void fetchInvoiceItems_usesReportApiWhenListRowsLackItemCode() {
        Map<String, Object> nameOnlyRow = Map.of("name", "line-1");
        List<Map<String, Object>> reportRows = List.of(Map.of(
                "item_code", "PIMS-BA3027E2",
                "qty", 2.0,
                "amount", 100.0,
                "parent", INVOICE));
        when(router.getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(nameOnlyRow))));
        when(router.callMethod(
                        eq(TENANT),
                        eq("pims.api.reports.list_sales_invoice_items"),
                        anyMap(),
                        any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextMessageResponse<>(reportRows)));

        StepVerifier.create(support.fetchInvoiceItems(TENANT, List.of(INVOICE)))
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).get("item_code")).isEqualTo("PIMS-BA3027E2");
                })
                .verifyComplete();
    }

    private static ErpNextDoc invoice(Map<String, Object> fields) throws Exception {
        return MAPPER.convertValue(fields, ErpNextDoc.class);
    }
}
