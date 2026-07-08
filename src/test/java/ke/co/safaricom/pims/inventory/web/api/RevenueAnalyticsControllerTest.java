package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.config.AbstractInventoryControllerTest;
import ke.co.safaricom.pims.inventory.web.model.RevenueAnalyticsSchemas;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RevenueAnalyticsControllerTest extends AbstractInventoryControllerTest {

    @Test
    void revenueAnalytics_returns_200() {
        when(revenueAnalyticsService.getRevenueAnalytics(eq("t1"), isNull(), eq("2026-02-01"), eq("2026-02-28"), isNull()))
                .thenReturn(Mono.just(new RevenueAnalyticsSchemas.RevenueAnalyticsResponse(
                        new RevenueAnalyticsSchemas.MoneyMetricCard(300000, "KES", 6.2, "up"),
                        new RevenueAnalyticsSchemas.MoneyMetricCard(292000, "KES", 6.2, "up"),
                        new RevenueAnalyticsSchemas.MoneyMetricCard(2000, "KES", 0.8, "up"),
                        new RevenueAnalyticsSchemas.PercentMetricCard(45.9, "%", 0.8, "up"))));

        client.get()
                .uri("/api/v1/inventory/reports/revenue-analytics?from=2026-02-01&to=2026-02-28")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total_revenue.value").isEqualTo(300000.0)
                .jsonPath("$.gross_profit.trend").isEqualTo("up")
                .jsonPath("$.gross_profit_margin.unit").isEqualTo("%");

        verify(revenueAnalyticsService).getRevenueAnalytics("t1", null, "2026-02-01", "2026-02-28", null);
    }

    @Test
    void revenueTrends_returns_200() {
        when(revenueAnalyticsService.getRevenueTrends(
                        eq("t1"), isNull(), isNull(), isNull(), any(), eq("revenue")))
                .thenReturn(Mono.just(new RevenueAnalyticsSchemas.RevenueTrendsResponse(
                        "revenue",
                        List.of(new RevenueAnalyticsSchemas.TrendSeriesPoint("2026-05-08", 8200, 3100, 145)))));

        client.get()
                .uri("/api/v1/inventory/reports/revenue-trends?date=08-05-2026&metric=revenue")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.metric").isEqualTo("revenue")
                .jsonPath("$.series[0].units").isEqualTo(145);
    }

    @Test
    void revenueByCategory_returns_200() {
        when(revenueAnalyticsService.getRevenueByCategory(eq("t1"), isNull(), any(), any(), any(), eq(5)))
                .thenReturn(Mono.just(new RevenueAnalyticsSchemas.RevenueByCategoryResponse(
                        300000, "KES",
                        List.of(new RevenueAnalyticsSchemas.CategoryRevenue("Antibiotics", 95000)))));

        client.get()
                .uri("/api/v1/inventory/reports/revenue-by-category?limit=5&from=2026-02-01&to=2026-02-28")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.categories[0].category").isEqualTo("Antibiotics");
    }

    @Test
    void topSellingProducts_returns_200() {
        UUID productId = StableEntityIds.itemId("t1", "prod_ins100");
        when(revenueAnalyticsService.getTopSellingProducts(eq("t1"), isNull(), any(), any(), any(), eq(5)))
                .thenReturn(Mono.just(new RevenueAnalyticsSchemas.TopSellingProductsResponse(List.of(
                        new RevenueAnalyticsSchemas.TopProduct(
                                1, productId, "Insulin Glargine 100ml", 140, 15600, "KES", "up")))));

        client.get()
                .uri("/api/v1/inventory/reports/top-selling-products?limit=5")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.products[0].rank").isEqualTo(1)
                .jsonPath("$.products[0].units_sold").isEqualTo(140);
    }

    @Test
    void revenueByPaymentMethod_returns_200() {
        when(revenueAnalyticsService.getRevenueByPaymentMethod(eq("t1"), isNull(), any(), any(), any()))
                .thenReturn(Mono.just(new RevenueAnalyticsSchemas.RevenueByPaymentMethodResponse(
                        4000, "KES",
                        List.of(new RevenueAnalyticsSchemas.PaymentMethodBreakdown(
                                "mpesa", 2000, 50.0, 9.3, "up")))));

        client.get()
                .uri("/api/v1/inventory/reports/revenue-by-payment-method?from=2026-02-02&to=2026-02-02")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.methods[0].method").isEqualTo("mpesa")
                .jsonPath("$.methods[0].pct").isEqualTo(50.0);
    }

    @Test
    void transactionTypes_returns_200() {
        when(revenueAnalyticsService.getTransactionTypes(eq("t1"), isNull(), any(), any(), any()))
                .thenReturn(Mono.just(new RevenueAnalyticsSchemas.TransactionTypesResponse(List.of(
                        new RevenueAnalyticsSchemas.TransactionTypeMonth("2026-01", 620, 810)))));

        client.get()
                .uri("/api/v1/inventory/reports/transaction-types")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].otc").isEqualTo(620)
                .jsonPath("$.data[0].prescription").isEqualTo(810);
    }

    @Test
    void revenueOverview_returns_200() {
        UUID productId = StableEntityIds.itemId("t1", "prod_amoxy500");
        when(revenueAnalyticsService.getRevenueOverview(
                        eq("t1"), isNull(), any(), any(), any(), eq(1), eq(5), eq("Antibiotics")))
                .thenReturn(Mono.just(new RevenueAnalyticsSchemas.RevenueOverviewResponse(
                        1, 5, 1, 1,
                        List.of(new RevenueAnalyticsSchemas.RevenueOverviewRow(
                                "2026-01", productId, "Amoxyl 500mg", "Amoxicillin 500mg",
                                "Antibiotics", 250000, "KES", 240, 300)))));

        client.get()
                .uri("/api/v1/inventory/reports/revenue-overview?page=1&page_size=5&category=Antibiotics")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(1)
                .jsonPath("$.page_size").isEqualTo(5)
                .jsonPath("$.data[0].avg_price").isEqualTo(300.0);
    }
}
