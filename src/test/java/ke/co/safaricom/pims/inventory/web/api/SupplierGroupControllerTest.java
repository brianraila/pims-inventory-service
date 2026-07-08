package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.api.dto.CreateSupplierGroupRequest;
import ke.co.safaricom.pims.inventory.api.dto.SupplierGroupResponse;
import ke.co.safaricom.pims.inventory.config.AbstractInventoryControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class SupplierGroupControllerTest extends AbstractInventoryControllerTest {

    private static final String BASE = "/api/v1/inventory/supplier-groups";

    @Test
    void listSupplierGroups_returns_200_with_list() {
        List<SupplierGroupResponse> groups = List.of(
                new SupplierGroupResponse("Wholesale Distributors", "Wholesale Distributors", "All Supplier Groups", false),
                new SupplierGroupResponse("Retail Pharmacies", "Retail Pharmacies", "All Supplier Groups", false));
        when(supplierService.listSupplierGroups(eq("t1"))).thenReturn(Mono.just(groups));

        client.get().uri(BASE)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo("Wholesale Distributors")
                .jsonPath("$[0].name").isEqualTo("Wholesale Distributors")
                .jsonPath("$[0].isGroup").isEqualTo(false);
    }

    @Test
    void createSupplierGroup_returns_201_with_created_group() {
        SupplierGroupResponse created = new SupplierGroupResponse(
                "Wholesale Distributors", "Wholesale Distributors", "All Supplier Groups", false);
        when(supplierService.createSupplierGroup(eq("t1"), any(CreateSupplierGroupRequest.class)))
                .thenReturn(Mono.just(created));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Wholesale Distributors\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo("Wholesale Distributors")
                .jsonPath("$.name").isEqualTo("Wholesale Distributors");
    }

    @Test
    void updateSupplierGroup_returns_200_with_updated_group() {
        SupplierGroupResponse updated = new SupplierGroupResponse(
                "Wholesale Distributors", "Wholesale Distributors Updated", "All Supplier Groups", false);
        when(supplierService.updateSupplierGroup(eq("t1"), eq("Wholesale Distributors"), any(CreateSupplierGroupRequest.class)))
                .thenReturn(Mono.just(updated));

        client.put().uri(BASE + "/Wholesale Distributors")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Wholesale Distributors Updated\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Wholesale Distributors Updated");
    }

    @Test
    void deleteSupplierGroup_returns_204() {
        when(supplierService.deleteSupplierGroup(eq("t1"), eq("Wholesale Distributors")))
                .thenReturn(Mono.empty());

        client.delete().uri(BASE + "/Wholesale Distributors")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void createSupplierGroup_returns_400_when_name_blank() {
        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
