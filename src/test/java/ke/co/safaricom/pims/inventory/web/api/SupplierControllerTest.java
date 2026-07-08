package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.api.dto.CreateSupplierRequest;
import ke.co.safaricom.pims.inventory.api.dto.SupplierPage;
import ke.co.safaricom.pims.inventory.api.dto.SupplierResponse;
import ke.co.safaricom.pims.inventory.config.AbstractInventoryControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class SupplierControllerTest extends AbstractInventoryControllerTest {

    private static final String BASE = "/api/v1/inventory/suppliers";

    @Test
    void listSuppliers_returns_200_with_paginated_envelope() {
        SupplierResponse supplier = sampleSupplier("SUP-001", "Lifeadd Chemist Limited");
        SupplierPage page = new SupplierPage(List.of(supplier), 0, 20, false, false);
        when(supplierService.listSuppliers(eq("t1"), any(), eq(0), eq(20))).thenReturn(Mono.just(page));

        client.get().uri(BASE + "?page=0&size=20")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo("SUP-001")
                .jsonPath("$.items[0].supplierName").isEqualTo("Lifeadd Chemist Limited")
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.hasNext").isEqualTo(false)
                .jsonPath("$.hasPrevious").isEqualTo(false);
    }

    @Test
    void getSupplier_returns_200_with_supplier() {
        SupplierResponse supplier = sampleSupplier("SUP-001", "Lifeadd Chemist Limited");
        when(supplierService.getSupplier(eq("t1"), eq("SUP-001"))).thenReturn(Mono.just(supplier));

        client.get().uri(BASE + "/SUP-001")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("SUP-001")
                .jsonPath("$.supplierName").isEqualTo("Lifeadd Chemist Limited")
                .jsonPath("$.status").isEqualTo("Active");
    }

    @Test
    void createSupplier_returns_201_with_created_supplier() {
        SupplierResponse created = sampleSupplier("SUP-NEW", "New Supplier Ltd");
        when(supplierService.createSupplier(eq("t1"), any(CreateSupplierRequest.class)))
                .thenReturn(Mono.just(created));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "supplierName": "New Supplier Ltd",
                          "supplierType": "Company",
                          "registrationNumber": "PPB/L/10072"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo("SUP-NEW")
                .jsonPath("$.supplierName").isEqualTo("New Supplier Ltd");
    }

    @Test
    void updateSupplier_returns_200_with_updated_supplier() {
        SupplierResponse updated = sampleSupplier("SUP-001", "Renamed Supplier Ltd");
        when(supplierService.updateSupplier(eq("t1"), eq("SUP-001"), any(CreateSupplierRequest.class)))
                .thenReturn(Mono.just(updated));

        client.put().uri(BASE + "/SUP-001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "supplierName": "Renamed Supplier Ltd",
                          "supplierType": "Company",
                          "disabled": true
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("SUP-001")
                .jsonPath("$.supplierName").isEqualTo("Renamed Supplier Ltd");
    }

    @Test
    void deleteSupplier_returns_204() {
        when(supplierService.deleteSupplier(eq("t1"), eq("SUP-001"))).thenReturn(Mono.empty());

        client.delete().uri(BASE + "/SUP-001")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void createSupplier_returns_400_when_supplier_name_blank() {
        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"supplierName\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    private SupplierResponse sampleSupplier(String id, String name) {
        return new SupplierResponse(
                id,
                name,
                "All Supplier Groups",
                "Company",
                "PPB/L/10072",
                "Kenya",
                "info@example.co.ke",
                "+254712345678",
                "Nairobi",
                "Westlands Road",
                "PPB/L/10072",
                "Retail",
                "31/12/2026",
                "Limited Company",
                "Test notes",
                "Licence No: PPB/L/10072",
                false,
                "Active",
                "admin",
                "admin",
                "2026-01-01 10:00:00",
                "2026-01-02 10:00:00"
        );
    }
}
