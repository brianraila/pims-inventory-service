package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.config.AbstractInventoryControllerTest;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class ManufacturersControllerTest extends AbstractInventoryControllerTest {

    private static final String BASE = "/api/v1/inventory/manufacturers";

    @Test
    void listManufacturers_returns_200_with_data_wrapper() {
        List<InventoryApiSchemas.Manufacturer> mfrs = List.of(
                new InventoryApiSchemas.Manufacturer(UUID.randomUUID(), "Teva Pharmaceuticals", "IL", true),
                new InventoryApiSchemas.Manufacturer(UUID.randomUUID(), "GSK plc", "GB", true));
        when(productInventoryService.manufacturers(isNull(), isNull())).thenReturn(Mono.just(mfrs));

        client.get().uri(BASE)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isArray()
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[0].name").isEqualTo("Teva Pharmaceuticals");
    }

    @Test
    void listManufacturers_passes_search_and_limit_params() {
        List<InventoryApiSchemas.Manufacturer> mfrs = List.of(
                new InventoryApiSchemas.Manufacturer(UUID.randomUUID(), "Teva Pharmaceuticals", "IL", true));
        when(productInventoryService.manufacturers(eq("Teva"), eq(2))).thenReturn(Mono.just(mfrs));

        client.get().uri(uriBuilder -> uriBuilder.path(BASE)
                        .queryParam("search", "Teva")
                        .queryParam("limit", 2)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].name").isEqualTo("Teva Pharmaceuticals");
    }

    @Test
    void listManufacturers_empty_result_returns_empty_data_array() {
        when(productInventoryService.manufacturers(eq("NONEXISTENT"), isNull())).thenReturn(Mono.just(List.of()));

        client.get().uri(uriBuilder -> uriBuilder.path(BASE)
                        .queryParam("search", "NONEXISTENT")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isArray()
                .jsonPath("$.data.length()").isEqualTo(0);
    }

    @Test
    void listManufacturers_response_includes_is_active_and_country_fields() {
        UUID id = UUID.randomUUID();
        List<InventoryApiSchemas.Manufacturer> mfrs = List.of(
                new InventoryApiSchemas.Manufacturer(id, "Lab & Allied", "KE", true));
        when(productInventoryService.manufacturers(isNull(), isNull())).thenReturn(Mono.just(mfrs));

        client.get().uri(BASE)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].id").isEqualTo(id.toString())
                .jsonPath("$.data[0].country").isEqualTo("KE")
                .jsonPath("$.data[0].is_active").isEqualTo(true);
    }
}
