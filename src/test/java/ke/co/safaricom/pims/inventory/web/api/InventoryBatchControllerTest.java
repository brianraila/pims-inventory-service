package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.config.AbstractInventoryControllerTest;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class InventoryBatchControllerTest extends AbstractInventoryControllerTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final String BASE = "/api/v1/inventory/products/" + PRODUCT_ID + "/batches";

    // ---- GET /products/{id}/batches --------------------------------------------

    @Test
    void listBatches_returns_200_with_batch_list() {
        InventoryApiSchemas.BatchListResponse response =
                new InventoryApiSchemas.BatchListResponse(List.of(),
                        new InventoryApiSchemas.Pagination(1, 10, 0, 0));
        when(productInventoryService.listBatches(eq("t1"), eq(PRODUCT_ID), anyInt(), anyInt(), any(), any()))
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
    void listBatches_passes_status_and_sort_params() {
        InventoryApiSchemas.BatchListResponse response =
                new InventoryApiSchemas.BatchListResponse(List.of(),
                        new InventoryApiSchemas.Pagination(1, 10, 0, 0));
        when(productInventoryService.listBatches(eq("t1"), eq(PRODUCT_ID), eq(1), eq(10),
                eq(Enums.BatchStatus.available), eq("lifo")))
                .thenReturn(Mono.just(response));

        client.get().uri(uriBuilder -> uriBuilder.path(BASE)
                        .queryParam("status", "available")
                        .queryParam("sort", "lifo")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk();
    }

    // ---- POST /products/{id}/batches (JSON) ------------------------------------

    @Test
    void createBatch_json_returns_201_with_batch() {
        InventoryApiSchemas.Batch batch = batch();
        when(productInventoryService.addBatchJsonReturn(eq("t1"), eq(PRODUCT_ID), any()))
                .thenReturn(Mono.just(batch));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"batch_number\":\"BATCH-001\",\"expiry_date\":\"2027-01-01\",\"quantity\":100,\"unit_cost\":12.5,\"storage_location\":\"Main\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo(BATCH_ID.toString())
                .jsonPath("$.batch_number").isEqualTo("BATCH-001");
    }

    // ---- POST /products/{id}/batches (multipart CSV) ---------------------------

    @Test
    void createBatch_csv_multipart_returns_201_with_bulk_result() {
        InventoryApiSchemas.BatchBulkUploadResult result =
                new InventoryApiSchemas.BatchBulkUploadResult(2, 2, 0, List.of(), List.of());
        when(productInventoryService.createBatchFlexible(eq("t1"), eq(PRODUCT_ID), isNull(), any()))
                .thenReturn(Mono.just(result));

        String csvContent = "batch_number,expiry_date,quantity,unit_cost,storage_location\n" +
                "BATCH-001,2027-01-01,100,12.5,Main\n" +
                "BATCH-002,2027-06-01,50,10.0,Cold Storage\n";

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new ByteArrayResource(csvContent.getBytes()) {
            @Override
            public String getFilename() {
                return "batches.csv";
            }
        }).contentType(MediaType.TEXT_PLAIN);

        client.post().uri(BASE)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.total_rows").isEqualTo(2)
                .jsonPath("$.success_count").isEqualTo(2)
                .jsonPath("$.error_count").isEqualTo(0);
    }

    // ---- GET /products/{id}/batches/{batch_id} ---------------------------------

    @Test
    void getBatch_returns_200_with_batch() {
        InventoryApiSchemas.Batch batch = batch();
        when(productInventoryService.getBatch(eq("t1"), eq(PRODUCT_ID), eq(BATCH_ID)))
                .thenReturn(Mono.just(batch));

        client.get().uri(BASE + "/" + BATCH_ID)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(BATCH_ID.toString());
    }

    @Test
    void getBatch_returns_404_when_batch_not_found() {
        when(productInventoryService.getBatch(eq("t1"), eq(PRODUCT_ID), any()))
                .thenReturn(Mono.error(new ResourceNotFoundException("Batch not found")));

        client.get().uri(BASE + "/" + UUID.randomUUID())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    // ---- DELETE /products/{id}/batches/{batch_id} ------------------------------

    @Test
    void deleteBatch_returns_204_no_content() {
        when(productInventoryService.deleteBatch(eq("t1"), eq(PRODUCT_ID), eq(BATCH_ID)))
                .thenReturn(Mono.empty());

        client.delete().uri(BASE + "/" + BATCH_ID)
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
    }

    @Test
    void deleteBatch_returns_404_when_batch_not_found() {
        when(productInventoryService.deleteBatch(eq("t1"), eq(PRODUCT_ID), any()))
                .thenReturn(Mono.error(new ResourceNotFoundException("Batch not found")));

        client.delete().uri(BASE + "/" + UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ---- helpers ---------------------------------------------------------------

    private InventoryApiSchemas.Batch batch() {
        return new InventoryApiSchemas.Batch(
                BATCH_ID, PRODUCT_ID, "BATCH-001", Enums.BatchStatus.available,
                100.0, Enums.UnitOfMeasure.capsules, "2024-01-01", "2027-01-01",
                "Supplier A", null, 12.5, 1250.0, "KES", "Main Warehouse",
                null, null, null, "Antibiotics", "", "");
    }
}
