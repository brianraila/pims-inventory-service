package ke.co.safaricom.pims.inventory.api;

import ke.co.safaricom.pims.inventory.api.dto.CreatePurchaseOrderRequest;
import ke.co.safaricom.pims.inventory.api.dto.PurchaseOrderResponse;
import ke.co.safaricom.pims.inventory.config.AbstractInventoryControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class PurchaseOrderControllerTest extends AbstractInventoryControllerTest {

    private static final String BASE = "/api/v1/inventory/purchase-orders";

    // ---- GET /inventory/purchase-orders ----------------------------------------

    @Test
    void listPurchaseOrders_returns_200_with_list() {
        List<PurchaseOrderResponse> orders = List.of(
                poResponse("PO-001", "Supplier A"),
                poResponse("PO-002", "Supplier B"));
        when(purchaseOrderService.listPurchaseOrders(eq("t1"))).thenReturn(Mono.just(orders));

        client.get().uri(BASE)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo("PO-001")
                .jsonPath("$[0].supplier").isEqualTo("Supplier A");
    }

    @Test
    void listPurchaseOrders_returns_empty_array_when_no_orders() {
        when(purchaseOrderService.listPurchaseOrders(eq("t1"))).thenReturn(Mono.just(List.of()));

        client.get().uri(BASE)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(0);
    }

    // ---- POST /inventory/purchase-orders ----------------------------------------

    @Test
    void createPurchaseOrder_returns_201_with_created_order() {
        PurchaseOrderResponse created = poResponse("PO-NEW-001", "Supplier C");
        when(purchaseOrderService.createPurchaseOrder(eq("t1"), any(CreatePurchaseOrderRequest.class)))
                .thenReturn(Mono.just(created));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"supplier\":\"Supplier C\",\"items\":[{\"itemCode\":\"PIMS-ITEM-001\",\"qty\":10}]}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo("PO-NEW-001")
                .jsonPath("$.supplier").isEqualTo("Supplier C");
    }

    @Test
    void createPurchaseOrder_returns_400_when_supplier_missing() {
        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"items\":[{\"itemCode\":\"PIMS-ITEM-001\",\"qty\":10}]}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createPurchaseOrder_returns_400_when_items_empty() {
        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"supplier\":\"Supplier A\",\"items\":[]}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createPurchaseOrder_includes_optional_fields_when_provided() {
        PurchaseOrderResponse created = new PurchaseOrderResponse(
                "PO-FULL-001", "PO-FULL-001", "Supplier D",
                "2024-01-15", "2024-02-15", null, "Draft", 2, 5000.0);
        when(purchaseOrderService.createPurchaseOrder(eq("t1"), any()))
                .thenReturn(Mono.just(created));

        client.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"supplier\":\"Supplier D\",\"transactionDate\":\"2024-01-15\",\"scheduleDate\":\"2024-02-15\",\"notes\":\"Urgent order\",\"items\":[{\"itemCode\":\"ITEM-001\",\"qty\":5,\"rate\":1000.0}]}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.orderDate").isEqualTo("2024-01-15")
                .jsonPath("$.expectedDate").isEqualTo("2024-02-15")
                .jsonPath("$.status").isEqualTo("Draft");
    }

    // ---- helpers ---------------------------------------------------------------

    private PurchaseOrderResponse poResponse(String id, String supplier) {
        return new PurchaseOrderResponse(id, id, supplier, "2024-01-15", "2024-02-15",
                null, "Draft", 1, 1000.0);
    }
}
