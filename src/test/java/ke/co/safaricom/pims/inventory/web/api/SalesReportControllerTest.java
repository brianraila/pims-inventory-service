package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.config.AbstractInventoryControllerTest;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.model.SalesReportSchemas;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesReportControllerTest extends AbstractInventoryControllerTest {

    private static final String BASE = "/api/v1/inventory/sales";

    @Test
    void getSalesReport_returns_200_with_report_payload() {
        UUID productId = UUID.fromString("86d322df-5426-3db4-9d81-947b0a82416a");
        SalesReportSchemas.SalesRow row = new SalesReportSchemas.SalesRow(
                "2026-01", productId, "Amoxyl 500mg", "Amoxicillin 500mg",
                "Antibiotics", 240, 250_000.0, "KES");
        InventoryApiSchemas.Pagination pagination = new InventoryApiSchemas.Pagination(1, 10, 45, 5);
        SalesReportSchemas.SalesSummary summary =
                new SalesReportSchemas.SalesSummary(750_000.0, 720, "KES");
        SalesReportSchemas.SalesReportResponse response =
                new SalesReportSchemas.SalesReportResponse(List.of(row), pagination, summary);

        when(salesReportService.getSalesReport(eq("t1"), eq("2026-01-01"), eq("2026-01-31"),
                eq("submitted"), eq(1), eq(10))).thenReturn(Mono.just(response));

        client.get()
                .uri(uriBuilder -> uriBuilder.path(BASE)
                        .queryParam("from", "2026-01-01")
                        .queryParam("to", "2026-01-31")
                        .queryParam("status", "submitted")
                        .queryParam("page", 1)
                        .queryParam("limit", 10)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].period").isEqualTo("2026-01")
                .jsonPath("$.data[0].product_id").isEqualTo(productId.toString())
                .jsonPath("$.data[0].units_sold").isEqualTo(240)
                .jsonPath("$.pagination.total_pages").isEqualTo(5)
                .jsonPath("$.summary.total_revenue").isEqualTo(750000.0)
                .jsonPath("$.summary.total_units").isEqualTo(720);

        verify(salesReportService).getSalesReport("t1", "2026-01-01", "2026-01-31", "submitted", 1, 10);
    }
}
