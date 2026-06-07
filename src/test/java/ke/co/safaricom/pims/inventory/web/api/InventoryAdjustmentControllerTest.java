package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.config.AbstractInventoryControllerTest;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class InventoryAdjustmentControllerTest extends AbstractInventoryControllerTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final UUID ADJ_ID = UUID.randomUUID();
    private static final String BASE = "/api/v1/inventory/products/" + PRODUCT_ID + "/adjustments";

    // ---- GET /products/{id}/adjustments ----------------------------------------

    @Test
    void listAdjustments_returns_200_with_adjustment_list() {
        InventoryApiSchemas.AdjustmentListResponse response =
                new InventoryApiSchemas.AdjustmentListResponse(List.of(),
                        new InventoryApiSchemas.Pagination(1, 10, 0, 0));
        when(productInventoryService.listAdjustments(eq("t1"), eq(PRODUCT_ID), anyInt(), anyInt()))
                .thenReturn(Mono.just(response));

        client.get().uri(BASE)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isArray()
                .jsonPath("$.pagination.page").isEqualTo(1);
    }

    @Test
    void listAdjustments_passes_pagination_params() {
        InventoryApiSchemas.AdjustmentListResponse response =
                new InventoryApiSchemas.AdjustmentListResponse(List.of(),
                        new InventoryApiSchemas.Pagination(2, 5, 0, 0));
        when(productInventoryService.listAdjustments(eq("t1"), eq(PRODUCT_ID), eq(2), eq(5)))
                .thenReturn(Mono.just(response));

        client.get().uri(uriBuilder -> uriBuilder.path(BASE)
                        .queryParam("page", 2)
                        .queryParam("limit", 5)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk();
    }

    // ---- POST /products/{id}/adjustments ----------------------------------------

    @Test
    void createAdjustment_returns_201_with_stock_adjustment() {
        InventoryApiSchemas.StockAdjustment adjustment = stockAdjustment();
        when(productInventoryService.adjustStock(eq("t1"), eq(PRODUCT_ID), any(), anyString(), anyString()))
                .thenReturn(Mono.just(adjustment));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"batch_id\":\"" + BATCH_ID + "\",\"adjustment_type\":\"increase\",\"quantity\":10,\"reason\":\"correction\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo(ADJ_ID.toString())
                .jsonPath("$.adjustment_type").isEqualTo("increase")
                .jsonPath("$.quantity").isEqualTo(10.0);
    }

    @Test
    void createAdjustment_returns_400_when_cannot_decrease_empty_batch() {
        when(productInventoryService.adjustStock(eq("t1"), eq(PRODUCT_ID), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new ServiceValidationException("Cannot decrease empty batch")));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"batch_id\":\"" + BATCH_ID + "\",\"adjustment_type\":\"decrease\",\"quantity\":5,\"reason\":\"damaged\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("BAD_REQUEST")
                .jsonPath("$.message").isEqualTo("Cannot decrease empty batch");
    }

    @Test
    void createAdjustment_extracts_anonymous_user_when_no_jwt() {
        InventoryApiSchemas.StockAdjustment adjustment = stockAdjustment();
        when(productInventoryService.adjustStock(eq("t1"), eq(PRODUCT_ID), any(), eq(""), eq("anonymous")))
                .thenReturn(Mono.just(adjustment));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"batch_id\":\"" + BATCH_ID + "\",\"adjustment_type\":\"increase\",\"quantity\":10,\"reason\":\"correction\"}")
                .exchange()
                .expectStatus().isCreated();
    }

    // ---- helpers ---------------------------------------------------------------

    private InventoryApiSchemas.StockAdjustment stockAdjustment() {
        return new InventoryApiSchemas.StockAdjustment(
                ADJ_ID, PRODUCT_ID, BATCH_ID, "BATCH-001",
                Enums.AdjustmentDirection.increase, 10.0, 50.0, 60.0,
                Enums.AdjustmentReason.correction, null, null,
                new InventoryApiSchemas.UserRef(UUID.randomUUID(), "anonymous", ""),
                "2024-01-15");
    }
}
