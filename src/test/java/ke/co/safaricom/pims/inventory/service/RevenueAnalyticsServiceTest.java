package ke.co.safaricom.pims.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueAnalyticsServiceTest {

    private static final String TENANT = "test-tenant";
    private static final String ITEM = "PIMS-ITEM-001";
    private static final String INV_CUR = "SINV-CUR";
    private static final String INV_PRIOR = "SINV-PRIOR";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ErpNextTenantRouter router;
    @Mock
    private InventoryService inventoryService;

    private RevenueAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new RevenueAnalyticsService(new SalesInvoiceReportSupport(router), inventoryService);
        stubReportApiInvoiceItemsFallback();
    }

    @Test
    void resolveDateRange_parses_design_date_alias() {
        SalesInvoiceReportSupport.DateRange range =
                SalesInvoiceReportSupport.resolveDateRange(null, null, null, "01-02-2026,28-02-2026");
        assertThat(range.from()).isEqualTo("2026-02-01");
        assertThat(range.to()).isEqualTo("2026-02-28");
    }

    @Test
    void computeTrend_detects_up_and_down() {
        assertThat(SalesInvoiceReportSupport.computeTrend(110, 100).trend()).isEqualTo("up");
        assertThat(SalesInvoiceReportSupport.computeTrend(90, 100).trend()).isEqualTo("down");
        assertThat(SalesInvoiceReportSupport.computeTrend(100, 100).trend()).isEqualTo("flat");
    }

    @Test
    void getRevenueAnalytics_computes_kpis_and_change() throws Exception {
        stubCatalog();
        // Current period: revenue 1000, COGS 240*10= nothing wait qty 10 amount 1000 cost 100 each => cogs 1000? 
        // Use qty 10 @ amount 1000, unit cost 10 => cogs 100, gross 900, tax 50 => net 850
        ErpNextDoc currentInv = invoice(Map.of(
                "name", INV_CUR,
                "posting_date", "2026-02-15",
                "grand_total", 1000.0,
                "total_taxes_and_charges", 50.0,
                "currency", "KES",
                "docstatus", 1,
                "custom_pims_sale_type", "otc"));
        ErpNextDoc priorInv = invoice(Map.of(
                "name", INV_PRIOR,
                "posting_date", "2026-01-15",
                "grand_total", 500.0,
                "total_taxes_and_charges", 25.0,
                "currency", "KES",
                "docstatus", 1,
                "custom_pims_sale_type", "otc"));

        when(router.getList(eq(TENANT), eq("Sales Invoice"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> params = inv.getArgument(2);
                    String filters = params.get("filters");
                    if (filters != null && filters.contains("2026-02-01")) {
                        return Mono.just(new ErpNextListResponse<>(List.of(currentInv)));
                    }
                    return Mono.just(new ErpNextListResponse<>(List.of(priorInv)));
                });

        when(router.getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> params = inv.getArgument(2);
                    String filters = params.get("filters");
                    if (filters != null && filters.contains(INV_CUR)) {
                        return Mono.just(new ErpNextListResponse<>(List.of(line(INV_CUR, 10, 1000))));
                    }
                    return Mono.just(new ErpNextListResponse<>(List.of(line(INV_PRIOR, 5, 500))));
                });

        StepVerifier.create(service.getRevenueAnalytics(TENANT, null, "2026-02-01", "2026-02-28", null))
                .assertNext(resp -> {
                    assertThat(resp.totalRevenue().value()).isEqualTo(1000.0);
                    assertThat(resp.grossProfit().value()).isEqualTo(900.0); // 1000 - 10*10
                    assertThat(resp.netProfit().value()).isEqualTo(850.0);   // 900 - 50
                    assertThat(resp.grossProfitMargin().value()).isEqualTo(90.0);
                    assertThat(resp.totalRevenue().trend()).isEqualTo("up");
                    assertThat(resp.totalRevenue().changePct()).isEqualTo(100.0);
                })
                .verifyComplete();
    }

    @Test
    void getRevenueByCategory_ranks_categories() throws Exception {
        stubCatalog();
        ErpNextDoc inv = invoice(Map.of(
                "name", INV_CUR,
                "posting_date", "2026-02-10",
                "grand_total", 1000.0,
                "currency", "KES",
                "docstatus", 1));
        stubSinglePeriodInvoices(List.of(inv));
        when(router.getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(line(INV_CUR, 10, 1000)))));

        StepVerifier.create(service.getRevenueByCategory(TENANT, null, "2026-02-01", "2026-02-28", null, 5))
                .assertNext(resp -> {
                    assertThat(resp.total()).isEqualTo(1000.0);
                    assertThat(resp.categories()).hasSize(1);
                    assertThat(resp.categories().get(0).category()).isEqualTo("Antibiotics");
                    assertThat(resp.categories().get(0).revenue()).isEqualTo(1000.0);
                })
                .verifyComplete();
    }

    @Test
    void getTopSellingProducts_includes_rank_and_trend() throws Exception {
        stubCatalog();
        ErpNextDoc currentInv = invoice(Map.of(
                "name", INV_CUR, "posting_date", "2026-02-10", "grand_total", 1000.0,
                "currency", "KES", "docstatus", 1));
        ErpNextDoc priorInv = invoice(Map.of(
                "name", INV_PRIOR, "posting_date", "2026-01-10", "grand_total", 200.0,
                "currency", "KES", "docstatus", 1));

        when(router.getList(eq(TENANT), eq("Sales Invoice"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> params = inv.getArgument(2);
                    String filters = params.get("filters");
                    if (filters != null && filters.contains("2026-02-01")) {
                        return Mono.just(new ErpNextListResponse<>(List.of(currentInv)));
                    }
                    return Mono.just(new ErpNextListResponse<>(List.of(priorInv)));
                });
        when(router.getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> params = inv.getArgument(2);
                    String filters = params.get("filters");
                    if (filters != null && filters.contains(INV_CUR)) {
                        return Mono.just(new ErpNextListResponse<>(List.of(line(INV_CUR, 140, 15600))));
                    }
                    return Mono.just(new ErpNextListResponse<>(List.of(line(INV_PRIOR, 50, 5000))));
                });

        StepVerifier.create(service.getTopSellingProducts(TENANT, null, "2026-02-01", "2026-02-28", null, 5))
                .assertNext(resp -> {
                    assertThat(resp.products()).hasSize(1);
                    assertThat(resp.products().get(0).rank()).isEqualTo(1);
                    assertThat(resp.products().get(0).unitsSold()).isEqualTo(140);
                    assertThat(resp.products().get(0).productId())
                            .isEqualTo(StableEntityIds.itemId(TENANT, ITEM));
                    assertThat(resp.products().get(0).trend()).isEqualTo("up");
                })
                .verifyComplete();
    }

    @Test
    void getTransactionTypes_splits_rx_and_otc_by_month() throws Exception {
        ErpNextDoc rx = invoice(Map.of(
                "name", "INV-RX", "posting_date", "2026-01-10", "grand_total", 100.0,
                "currency", "KES", "docstatus", 1, "custom_pims_prescription_id", "rx-1"));
        ErpNextDoc otc = invoice(Map.of(
                "name", "INV-OTC", "posting_date", "2026-01-12", "grand_total", 50.0,
                "currency", "KES", "docstatus", 1, "custom_pims_sale_type", "otc"));
        stubInvoicesWithSaleMetadata(List.of(rx, otc));

        StepVerifier.create(service.getTransactionTypes(TENANT, null, "2026-01-01", "2026-01-31", null))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(1);
                    assertThat(resp.data().get(0).month()).isEqualTo("2026-01");
                    assertThat(resp.data().get(0).otc()).isEqualTo(1);
                    assertThat(resp.data().get(0).prescription()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void getRevenueByPaymentMethod_groups_modes() throws Exception {
        stubCatalog();
        ErpNextDoc inv = invoice(Map.of(
                "name", INV_CUR, "posting_date", "2026-02-10", "grand_total", 2000.0,
                "currency", "KES", "docstatus", 1));
        when(router.getList(eq(TENANT), eq("Sales Invoice"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(inv))));
        when(router.getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(line(INV_CUR, 2, 2000)))));

        ErpNextDoc peCash = invoice(Map.of(
                "name", "PE-1", "mode_of_payment", "Cash", "paid_amount", 1000.0, "docstatus", 1));
        ErpNextDoc peMpesa = invoice(Map.of(
                "name", "PE-2", "mode_of_payment", "M PESA", "paid_amount", 1000.0, "docstatus", 1));
        when(router.getList(eq(TENANT), eq("Payment Entry"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(peCash, peMpesa))));

        StepVerifier.create(service.getRevenueByPaymentMethod(TENANT, null, "2026-02-01", "2026-02-28", null))
                .assertNext(resp -> {
                    assertThat(resp.total()).isEqualTo(2000.0);
                    assertThat(resp.methods()).hasSize(2);
                    assertThat(resp.methods().stream().map(m -> m.method()).toList())
                            .containsExactlyInAnyOrder("cash", "mpesa");
                })
                .verifyComplete();
    }

    @Test
    void getRevenueOverview_paginates_and_filters_category() throws Exception {
        stubCatalog();
        ErpNextDoc inv = invoice(Map.of(
                "name", INV_CUR, "posting_date", "2026-01-15", "grand_total", 250000.0,
                "currency", "KES", "docstatus", 1));
        stubSinglePeriodInvoices(List.of(inv));
        when(router.getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(line(INV_CUR, 240, 250000)))));

        StepVerifier.create(service.getRevenueOverview(
                        TENANT, null, "2026-01-01", "2026-01-31", null, 1, 10, "Antibiotics"))
                .assertNext(resp -> {
                    assertThat(resp.totalRecords()).isEqualTo(1);
                    assertThat(resp.data()).hasSize(1);
                    assertThat(resp.data().get(0).avgPrice()).isEqualTo(1041.67); // 250000/240 rounded
                    assertThat(resp.data().get(0).category()).isEqualTo("Antibiotics");
                })
                .verifyComplete();

        StepVerifier.create(service.getRevenueOverview(
                        TENANT, null, "2026-01-01", "2026-01-31", null, 1, 10, "Vitamins"))
                .assertNext(resp -> assertThat(resp.data()).isEmpty())
                .verifyComplete();
    }

    @Test
    void getRevenueTrends_fills_daily_series() throws Exception {
        stubCatalog();
        ErpNextDoc inv = invoice(Map.of(
                "name", INV_CUR, "posting_date", "2026-02-02", "grand_total", 100.0,
                "currency", "KES", "docstatus", 1));
        stubSinglePeriodInvoices(List.of(inv));
        when(router.getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(line(INV_CUR, 2, 100)))));

        StepVerifier.create(service.getRevenueTrends(TENANT, null, "2026-02-01", "2026-02-03", null, "revenue"))
                .assertNext(resp -> {
                    assertThat(resp.metric()).isEqualTo("revenue");
                    assertThat(resp.series()).hasSize(3);
                    assertThat(resp.series().get(0).date()).isEqualTo("2026-02-01");
                    assertThat(resp.series().get(0).revenue()).isZero();
                    assertThat(resp.series().get(1).date()).isEqualTo("2026-02-02");
                    assertThat(resp.series().get(1).revenue()).isEqualTo(100.0);
                    assertThat(resp.series().get(1).units()).isEqualTo(2);
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

    // ---- helpers --------------------------------------------------------------

    private void stubCatalog() {
        BatchResponse batch = new BatchResponse(
                "BATCH-1", "B1", ITEM, 100, "available", "2027-01-01",
                null, "Main", null, "2026-01-01", 10.0, 15.0, null, null);
        InventoryItemResponse item = new InventoryItemResponse(
                ITEM, "Amoxyl 500mg", "Amoxicillin 500mg", "Antibiotics",
                false, false, null, 50.0, 500.0, "Nos", List.of(batch), Map.of());
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(item)));
    }

    private void stubSinglePeriodInvoices(List<ErpNextDoc> invoices) {
        when(router.getList(eq(TENANT), eq("Sales Invoice"), anyMap(), any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(invoices)));
    }

    private void stubInvoicesWithSaleMetadata(List<ErpNextDoc> invoices) {
        when(router.callMethod(
                        eq(TENANT),
                        eq("pims.api.reports.list_sales_invoices"),
                        anyMap(),
                        any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(new ErpNextMessageResponse<>(invoices)));
    }

    private void stubInvoiceGetOne(ErpNextDoc... invoices) {
        for (ErpNextDoc invoice : invoices) {
            when(router.getOne(eq(TENANT), eq("Sales Invoice"), eq(invoice.name()), any(ParameterizedTypeReference.class)))
                    .thenReturn(Mono.just(new ErpNextSingleResponse<>(invoice)));
        }
    }

    private static Map<String, Object> line(String parent, double qty, double amount) {
        Map<String, Object> row = new HashMap<>();
        row.put("item_code", ITEM);
        row.put("item_name", "Amoxyl 500mg");
        row.put("qty", qty);
        row.put("amount", amount);
        row.put("parent", parent);
        return row;
    }

    private static ErpNextDoc invoice(Map<String, Object> fields) throws Exception {
        return MAPPER.convertValue(fields, ErpNextDoc.class);
    }
}
