package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.config.AbstractInventoryControllerTest;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.model.RevenueReportSchemas;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RevenueReportControllerTest extends AbstractInventoryControllerTest {

    @Test
    void getRevenueReport_returns_200_with_payload() {
        RevenueReportSchemas.RevenueReportResponse response = new RevenueReportSchemas.RevenueReportResponse(
                new RevenueReportSchemas.RevenuePeriod("2026-06-08", "2026-07-08", 30),
                750_000.0,
                120,
                6250.0,
                new RevenueReportSchemas.TransactionBucket(45, 300_000.0),
                new RevenueReportSchemas.TransactionBucket(75, 450_000.0),
                "KES");

        when(revenueReportService.getRevenueReport(eq("t1"), eq(30), isNull(), isNull(), eq("submitted")))
                .thenReturn(Mono.just(response));

        client.get()
                .uri("/api/v1/inventory/revenue?days=30&status=submitted")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total_revenue").isEqualTo(750000.0)
                .jsonPath("$.total_transactions").isEqualTo(120)
                .jsonPath("$.prescription_transactions.count").isEqualTo(45)
                .jsonPath("$.otc_transactions.count").isEqualTo(75);

        verify(revenueReportService).getRevenueReport("t1", 30, null, null, "submitted");
    }
}
