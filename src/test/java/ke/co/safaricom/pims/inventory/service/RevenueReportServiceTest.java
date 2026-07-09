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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueReportServiceTest {

    private static final String TENANT = "abc-pharmacy";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ErpNextTenantRouter router;

    private RevenueReportService service;

    @BeforeEach
    void setUp() {
        service = new RevenueReportService(new SalesInvoiceReportSupport(router));
    }

    @Test
    void getRevenueReport_splits_rx_and_otc() throws Exception {
        ErpNextDoc rx = invoice(Map.of(
                "name", "INV-RX",
                "grand_total", 1000.0,
                "custom_pims_prescription_id", "rx-1",
                "custom_pims_sale_type", "prescription",
                "currency", "KES",
                "docstatus", 1));
        ErpNextDoc otc = invoice(Map.of(
                "name", "INV-OTC",
                "grand_total", 500.0,
                "custom_pims_sale_type", "otc",
                "currency", "KES",
                "docstatus", 1));
        stubInvoices(List.of(rx, otc));

        StepVerifier.create(service.getRevenueReport(TENANT, 30, null, null, "submitted"))
                .assertNext(resp -> {
                    assertThat(resp.totalRevenue()).isEqualTo(1500.0);
                    assertThat(resp.totalTransactions()).isEqualTo(2);
                    assertThat(resp.avgOrderValue()).isEqualTo(750.0);
                    assertThat(resp.prescriptionTransactions().count()).isEqualTo(1);
                    assertThat(resp.otcTransactions().count()).isEqualTo(1);
                })
                .verifyComplete();
    }

    private static ErpNextDoc invoice(Map<String, Object> fields) throws Exception {
        return MAPPER.convertValue(fields, ErpNextDoc.class);
    }

    private void stubInvoices(List<ErpNextDoc> invoices) {
        when(router.callMethod(
                        eq(TENANT),
                        eq("pims.api.reports.list_sales_invoices"),
                        anyMap(),
                        any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextMessageResponse<>(invoices)));
    }
}
