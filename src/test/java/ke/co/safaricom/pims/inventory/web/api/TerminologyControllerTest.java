package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.config.TestSecurityConfig;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = TerminologyController.class)
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class})
class TerminologyControllerTest {

    private static final String SEARCH_BASE = "/api/v1/terminology/search";
    private static final String PRODUCT_BASE = "/api/v1/terminology/products";

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

    // ---- GET /terminology/search -----------------------------------------------

    @Test
    void searchTerminology_returns_200_with_results_for_valid_query() {
        InventoryApiSchemas.TerminologySearchResponse response =
                new InventoryApiSchemas.TerminologySearchResponse(
                        List.of(new InventoryApiSchemas.TerminologySearchResult(
                                "rxn-1001", Enums.TerminologyRecordSource.rxnorm,
                                "Amoxicillin 500mg", "Amoxicillin",
                                "Teva Pharmaceuticals", "0900-0100-01", "500mg", "Capsules")),
                        1,
                        List.of(Enums.TerminologyRecordSource.rxnorm));
        when(service.searchTerminology(eq("Am"), isNull(), eq(Enums.TerminologySource.all), eq(10)))
                .thenReturn(Mono.just(response));

        client.get().uri(uriBuilder -> uriBuilder.path(SEARCH_BASE)
                        .queryParam("q", "Am")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isArray()
                .jsonPath("$.data[0].terminology_id").isEqualTo("rxn-1001")
                .jsonPath("$.total").isEqualTo(1);
    }

    @Test
    void searchTerminology_returns_400_when_query_is_one_char() {
        client.get().uri(uriBuilder -> uriBuilder.path(SEARCH_BASE)
                        .queryParam("q", "A")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("BAD_REQUEST");
    }

    @Test
    void searchTerminology_returns_400_when_query_is_blank() {
        client.get().uri(uriBuilder -> uriBuilder.path(SEARCH_BASE)
                        .queryParam("q", " ")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void searchTerminology_passes_manufacturer_id_and_source_params() {
        UUID mfrId = UUID.randomUUID();
        InventoryApiSchemas.TerminologySearchResponse response =
                new InventoryApiSchemas.TerminologySearchResponse(List.of(), 0, List.of());
        when(service.searchTerminology(eq("amox"), eq(mfrId), eq(Enums.TerminologySource.rxnorm), eq(5)))
                .thenReturn(Mono.just(response));

        client.get().uri(uriBuilder -> uriBuilder.path(SEARCH_BASE)
                        .queryParam("q", "amox")
                        .queryParam("manufacturer_id", mfrId.toString())
                        .queryParam("source", "rxnorm")
                        .queryParam("limit", 5)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk();
    }

    // ---- GET /terminology/products/{terminology_id} ----------------------------

    @Test
    void getTerminologyProduct_returns_200_for_known_id() {
        InventoryApiSchemas.TerminologyProduct product = terminologyProduct();
        when(service.terminologyProduct(eq("rxn-1001"))).thenReturn(Mono.just(product));

        client.get().uri(PRODUCT_BASE + "/rxn-1001")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.terminology_id").isEqualTo("rxn-1001")
                .jsonPath("$.brand_name").isEqualTo("Amoxicillin 500mg Capsules");
    }

    @Test
    void getTerminologyProduct_returns_404_for_unknown_id() {
        when(service.terminologyProduct(eq("unknown-id")))
                .thenReturn(Mono.error(new ResourceNotFoundException("Terminology product not found")));

        client.get().uri(PRODUCT_BASE + "/unknown-id")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    // ---- helpers ---------------------------------------------------------------

    private InventoryApiSchemas.TerminologyProduct terminologyProduct() {
        return new InventoryApiSchemas.TerminologyProduct(
                "rxn-1001",
                Enums.TerminologyRecordSource.rxnorm,
                "Amoxicillin 500mg Capsules",
                "Amoxicillin",
                new InventoryApiSchemas.Manufacturer(UUID.randomUUID(), "Teva Pharmaceuticals", "IL", true),
                Enums.ProductCategory.Antibiotics,
                "00093-4157-01",
                "0900-0100-01",
                Enums.RegulatoryStatus.approved,
                "500mg",
                "Capsules");
    }
}
