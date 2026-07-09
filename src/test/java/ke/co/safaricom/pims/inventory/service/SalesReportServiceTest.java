package ke.co.safaricom.pims.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.web.model.SalesReportSchemas;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesReportServiceTest {

    private static final String TENANT = "test-tenant";
    private static final String ITEM_CODE = "PIMS-ITEM-001";
    private static final String INVOICE_ID = "SINV-2026-00001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> INVOICE_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextListResponse<Map<String, Object>>> ITEM_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    @Mock
    private ErpNextTenantRouter router;
    @Mock
    private InventoryService inventoryService;

    private SalesReportService service;

    @BeforeEach
    void setUp() {
        service = new SalesReportService(new SalesInvoiceReportSupport(router), inventoryService);
        stubReportApiInvoiceItemsFallback();
    }

    @Test
    void getSalesReport_aggregates_by_period_and_product() {
        ErpNextDoc invoice = invoiceDoc(INVOICE_ID, "2026-01-15");
        when(router.getList(eq(TENANT), eq("Sales Invoice"), anyMap(), eq(INVOICE_LIST_TYPE)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(invoice))));

        Map<String, Object> line = new HashMap<>();
        line.put("item_code", ITEM_CODE);
        line.put("item_name", "Amoxyl 500mg");
        line.put("qty", 240.0);
        line.put("amount", 250_000.0);
        line.put("parent", INVOICE_ID);
        when(router.getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(), eq(ITEM_LIST_TYPE)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(line))));

        InventoryItemResponse item = new InventoryItemResponse(
                ITEM_CODE, "Amoxyl 500mg", "Amoxicillin 500mg", "Antibiotics",
                false, false, null, 50.0, 500.0, "Nos", List.of(), Map.of());
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(item)));

        StepVerifier.create(service.getSalesReport(TENANT, "2026-01-01", "2026-01-31", "submitted", 1, 10))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(1);
                    SalesReportSchemas.SalesRow row = resp.data().get(0);
                    assertThat(row.period()).isEqualTo("2026-01");
                    assertThat(row.productId()).isEqualTo(StableEntityIds.itemId(TENANT, ITEM_CODE));
                    assertThat(row.productName()).isEqualTo("Amoxyl 500mg");
                    assertThat(row.genericName()).isEqualTo("Amoxicillin 500mg");
                    assertThat(row.category()).isEqualTo("Antibiotics");
                    assertThat(row.unitsSold()).isEqualTo(240);
                    assertThat(row.revenue()).isEqualTo(250_000.0);
                    assertThat(row.currency()).isEqualTo("KES");
                    assertThat(resp.summary().totalRevenue()).isEqualTo(250_000.0);
                    assertThat(resp.summary().totalUnits()).isEqualTo(240);
                    assertThat(resp.pagination().total()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void getSalesReport_returns_empty_when_no_invoices() {
        when(router.getList(eq(TENANT), eq("Sales Invoice"), anyMap(), eq(INVOICE_LIST_TYPE)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of())));

        StepVerifier.create(service.getSalesReport(TENANT, null, null, "submitted", 1, 10))
                .assertNext(resp -> {
                    assertThat(resp.data()).isEmpty();
                    assertThat(resp.summary().totalRevenue()).isZero();
                    assertThat(resp.summary().totalUnits()).isZero();
                })
                .verifyComplete();
    }


    private void stubReportApiInvoiceItemsFallback() {
        lenient().when(router.callMethod(
                        eq(TENANT),
                        eq("pims.api.reports.list_sales_invoice_items"),
                        anyMap(),
                        any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.error(new RuntimeException("test: use ERPNext list fallback")));
    }

    private ErpNextDoc invoiceDoc(String name, String postingDate) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("posting_date", postingDate);
        fields.put("currency", "KES");
        fields.put("docstatus", 1);
        fields.put("status", "Paid");
        return MAPPER.convertValue(fields, ErpNextDoc.class);
    }
}
