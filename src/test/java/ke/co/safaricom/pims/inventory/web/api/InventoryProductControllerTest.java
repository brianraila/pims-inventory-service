package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.config.TestSecurityConfig;
import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import ke.co.safaricom.pims.inventory.exception.handler.GlobalExceptionHandler;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.service.ProductInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = InventoryProductController.class)
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class})
class InventoryProductControllerTest {

    private static final String BASE = "/api/v1/inventory/products";
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID DRAFT_ID = UUID.randomUUID();

    @Autowired
    private WebTestClient client;

    @MockBean
    private ProductInventoryService service;

    @MockBean
    private TenantContextResolver tenants;

    @BeforeEach
    void setUp() {
        when(tenants.resolveTenantId(any(), any())).thenReturn(Mono.just("t1"));
    }

    // ---- GET /inventory/products ------------------------------------------------

    @Test
    void listProducts_returns_200_with_product_list() {
        InventoryApiSchemas.Pagination pagination = new InventoryApiSchemas.Pagination(1, 10, 0, 0);
        InventoryApiSchemas.ProductListResponse response =
                new InventoryApiSchemas.ProductListResponse(List.of(), pagination, null);
        when(service.listProducts(eq("t1"), anyInt(), anyInt(), any(), any(), any(), any()))
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
    void listProducts_passes_query_params_to_service() {
        InventoryApiSchemas.Pagination pagination = new InventoryApiSchemas.Pagination(1, 5, 0, 0);
        InventoryApiSchemas.ProductListResponse response =
                new InventoryApiSchemas.ProductListResponse(List.of(), pagination, null);
        when(service.listProducts(eq("t1"), eq(1), eq(5), eq("Amox"),
                eq(Enums.ProductCategory.Antibiotics), eq(Enums.ProductStatus.available), any()))
                .thenReturn(Mono.just(response));

        client.get().uri(uriBuilder -> uriBuilder.path(BASE)
                        .queryParam("page", 1)
                        .queryParam("limit", 5)
                        .queryParam("search", "Amox")
                        .queryParam("category", "Antibiotics")
                        .queryParam("status", "available")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk();
    }

    // ---- POST /inventory/products -----------------------------------------------

    @Test
    void createProduct_returns_201_with_product_detail() {
        InventoryApiSchemas.ProductDetail detail = productDetail();
        when(service.createProduct(eq("t1"), any(InventoryApiSchemas.CreateProductRequest.class)))
                .thenReturn(Mono.just(detail));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"product_name\":\"Amoxicillin\",\"generic_name\":\"Amox\",\"category\":\"Antibiotics\",\"unit_of_measure\":\"capsules\",\"reorder_level\":10,\"maximum_stock\":100}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo(PRODUCT_ID.toString());
    }

    @Test
    void createProduct_returns_409_on_duplicate_ppb_code() {
        when(service.createProduct(eq("t1"), any()))
                .thenReturn(Mono.error(new ConflictException("Duplicate PPB code")));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"product_name\":\"Dup\",\"generic_name\":\"Dup\",\"category\":\"Antibiotics\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONFLICT");
    }

    @Test
    void createProduct_returns_400_on_validation_error() {
        when(service.createProduct(eq("t1"), any()))
                .thenReturn(Mono.error(new ServiceValidationException("Missing required fields")));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"product_name\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("BAD_REQUEST");
    }

    // ---- POST /inventory/products/drafts ----------------------------------------

    @Test
    void saveDraft_returns_201_with_draft() {
        InventoryApiSchemas.ProductDraft draft = new InventoryApiSchemas.ProductDraft(
                DRAFT_ID, Enums.WizardStep.ONE, null, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z");
        when(service.saveDraft(eq("t1"), any())).thenReturn(Mono.just(draft));

        client.post().uri(BASE + "/drafts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"wizard_step\":1}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo(DRAFT_ID.toString())
                .jsonPath("$.wizard_step").isEqualTo(1);
    }

    // ---- GET /inventory/products/drafts/{draft_id} ------------------------------

    @Test
    void getDraft_returns_200_with_draft() {
        InventoryApiSchemas.ProductDraft draft = new InventoryApiSchemas.ProductDraft(
                DRAFT_ID, Enums.WizardStep.TWO, null, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z");
        when(service.getDraft(eq("t1"), eq(DRAFT_ID))).thenReturn(Mono.just(draft));

        client.get().uri(BASE + "/drafts/" + DRAFT_ID)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(DRAFT_ID.toString())
                .jsonPath("$.wizard_step").isEqualTo(2);
    }

    @Test
    void getDraft_returns_404_when_draft_not_found() {
        when(service.getDraft(eq("t1"), any())).thenReturn(Mono.error(new ResourceNotFoundException("Draft not found")));

        client.get().uri(BASE + "/drafts/" + UUID.randomUUID())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    // ---- PATCH /inventory/products/drafts/{draft_id} ----------------------------

    @Test
    void patchDraft_returns_200_with_updated_draft() {
        InventoryApiSchemas.ProductDraft draft = new InventoryApiSchemas.ProductDraft(
                DRAFT_ID, Enums.WizardStep.THREE, null, "2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z");
        when(service.patchDraft(eq("t1"), eq(DRAFT_ID), any())).thenReturn(Mono.just(draft));

        client.patch().uri(BASE + "/drafts/" + DRAFT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"wizard_step\":3}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.wizard_step").isEqualTo(3);
    }

    // ---- GET /inventory/products/{product_id} -----------------------------------

    @Test
    void getProduct_returns_200_with_product_detail() {
        InventoryApiSchemas.ProductDetail detail = productDetail();
        when(service.getProduct(eq("t1"), eq(PRODUCT_ID))).thenReturn(Mono.just(detail));

        client.get().uri(BASE + "/" + PRODUCT_ID)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(PRODUCT_ID.toString())
                .jsonPath("$.product_name").isEqualTo("Amoxicillin 500mg");
    }

    @Test
    void getProduct_returns_404_when_not_found() {
        when(service.getProduct(eq("t1"), any())).thenReturn(Mono.error(new ResourceNotFoundException("Product not found")));

        client.get().uri(BASE + "/" + UUID.randomUUID())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ---- PATCH /inventory/products/{product_id} ---------------------------------

    @Test
    void patchProduct_returns_200_with_updated_product() {
        InventoryApiSchemas.ProductDetail detail = productDetail();
        when(service.updateProduct(eq("t1"), eq(PRODUCT_ID), any())).thenReturn(Mono.just(detail));

        client.patch().uri(BASE + "/" + PRODUCT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"product_name\":\"Amoxicillin 500mg\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(PRODUCT_ID.toString());
    }

    // ---- DELETE /inventory/products/{product_id} --------------------------------

    @Test
    void deleteProduct_returns_204_no_content() {
        when(service.deleteProduct(eq("t1"), eq(PRODUCT_ID))).thenReturn(Mono.empty());

        client.delete().uri(BASE + "/" + PRODUCT_ID)
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
    }

    @Test
    void deleteProduct_returns_404_when_product_not_found() {
        when(service.deleteProduct(eq("t1"), any())).thenReturn(Mono.error(new ResourceNotFoundException("Product not found")));

        client.delete().uri(BASE + "/" + UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ---- helpers ----------------------------------------------------------------

    private InventoryApiSchemas.ProductDetail productDetail() {
        InventoryApiSchemas.BatchListResponse batches =
                new InventoryApiSchemas.BatchListResponse(List.of(),
                        new InventoryApiSchemas.Pagination(1, 10, 0, 0));
        return new InventoryApiSchemas.ProductDetail(
                PRODUCT_ID, "Amoxicillin 500mg", "Amoxicillin", Enums.ProductCategory.Antibiotics,
                100.0, Enums.UnitOfMeasure.capsules, 2, List.of(Enums.ProductStatus.available),
                null, "", "",
                null, "PPB-001", null, Enums.RegulatoryStatus.approved,
                "500mg", "Capsules", null, null, null,
                50.0, 500.0, null, 100.0, 1000.0, "KES", 400.0, List.of(), batches);
    }
}
