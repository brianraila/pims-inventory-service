package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.config.AbstractInventoryControllerTest;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.model.StockReportSchemas;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockReportControllerTest extends AbstractInventoryControllerTest {

    @Test
    void getStockReport_returns_200_with_payload() {
        StockReportSchemas.StockReportRow row = new StockReportSchemas.StockReportRow(
                "Amoxyl 500mg", "AMX-2024-001", 250.0, Enums.UnitOfMeasure.units,
                100.0, 25000.0, "2026-07-30", "active", null, 240.0, 0);
        InventoryApiSchemas.Pagination pagination = new InventoryApiSchemas.Pagination(1, 10, 1, 1);
        StockReportSchemas.StockReportResponse response =
                new StockReportSchemas.StockReportResponse(List.of(row), pagination);

        when(stockReportService.getStockReport(eq("t1"), eq(1), eq(10), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(response));

        client.get()
                .uri("/api/v1/inventory/stock-report?page=1&limit=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].product_name").isEqualTo("Amoxyl 500mg")
                .jsonPath("$.data[0].batch_number").isEqualTo("AMX-2024-001")
                .jsonPath("$.data[0].reorder_level").isEqualTo(240.0);

        verify(stockReportService).getStockReport("t1", 1, 10, null, null, null);
    }
}
