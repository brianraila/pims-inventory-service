package ke.co.safaricom.pims.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.config.ErpNextProperties;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import ke.co.safaricom.pims.inventory.web.model.SalesOrderSchemas;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SalesOrderServiceTest {

    private static final String TENANT = "test-tenant";
    private static final String ORDER_ID = "SINV-2024-00001";
    private static final String ITEM_CODE = "PIMS-ITEM-001";
    private static final String ITEM_NAME = "Amoxicillin 500mg";
    private static final String WAREHOUSE = "Main Warehouse";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextSingleResponse<Map<String, Object>>> RAW_SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextMessageResponse<ErpNextDoc>> SUBMIT_TYPE =
            new ParameterizedTypeReference<>() {};

    @Mock
    private ErpNextTenantRouter router;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private TaxConfigService taxConfigService;

    private SalesOrderService service;

    @BeforeEach
    void setUp() {
        ErpNextProperties properties = new ErpNextProperties(null, null, null, null, null, WAREHOUSE, null, null);
        service = new SalesOrderService(router, inventoryService, properties, taxConfigService);
    }

    // ---- helpers ------------------------------------------------------------

    private static ErpNextDoc doc(Map<String, Object> fields) {
        return MAPPER.convertValue(fields, ErpNextDoc.class);
    }

    private static ErpNextDoc invoiceDoc(int docstatus, double grandTotal) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", ORDER_ID);
        fields.put("docstatus", docstatus);
        fields.put("customer", "Jane Doe");
        fields.put("grand_total", grandTotal);
        return doc(fields);
    }

    private InventoryItemResponse item() {
        return new InventoryItemResponse(ITEM_CODE, ITEM_NAME, "", "Antibiotics",
                false, false, null, 50.0, 500.0, "Nos", List.of(), Map.of());
    }

    private SalesOrderSchemas.CreateOrderRequest createRequest(UUID productId, double qty, double unitPrice) {
        return new SalesOrderSchemas.CreateOrderRequest("Jane Doe", null,
                List.of(new SalesOrderSchemas.CreateOrderRequest.OrderItem(productId, qty, unitPrice)));
    }

    // ---- createDraft: stock-availability check -------------------------------

    @Test
    void createDraft_rejects_when_requested_quantity_exceeds_available_stock() {
        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(item())));
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE)))
                .thenReturn(Mono.just(Map.of(ITEM_CODE, 4.0)));

        StepVerifier.create(service.createDraft(TENANT, createRequest(productId, 10.0, 100.0)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ServiceValidationException.class);
                    assertThat(err.getMessage())
                            .contains("Insufficient stock for " + ITEM_NAME)
                            .contains("requested 10.0")
                            .contains("available 4.0");
                })
                .verify();

        verify(router, never()).create(eq(TENANT), eq("Sales Invoice"), anyMap(), eq(SINGLE_TYPE));
    }

    @Test
    void createDraft_creates_order_when_stock_sufficient() {
        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(item())));
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE)))
                .thenReturn(Mono.just(Map.of(ITEM_CODE, 100.0)));
        when(taxConfigService.getDefaultTaxTemplateName(TENANT)).thenReturn(Mono.empty());
        when(router.create(eq(TENANT), eq("Sales Invoice"), anyMap(), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(invoiceDoc(0, 1000.0))));

        StepVerifier.create(service.createDraft(TENANT, createRequest(productId, 10.0, 100.0)))
                .assertNext(resp -> {
                    assertThat(resp.orderId()).isEqualTo(ORDER_ID);
                    assertThat(resp.status()).isEqualTo("draft");
                    assertThat(resp.items()).hasSize(1);
                    assertThat(resp.items().get(0).productName()).isEqualTo(ITEM_NAME);
                })
                .verifyComplete();
    }

    // ---- updateItems: stock-availability check -------------------------------

    @Test
    void updateItems_rejects_when_requested_quantity_exceeds_available_stock() {
        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(invoiceDoc(0, 1000.0))));
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(item())));
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE)))
                .thenReturn(Mono.just(Map.of(ITEM_CODE, 2.0)));

        SalesOrderSchemas.UpdateOrderItemsRequest req = new SalesOrderSchemas.UpdateOrderItemsRequest(
                List.of(new SalesOrderSchemas.CreateOrderRequest.OrderItem(productId, 5.0, 100.0)));

        StepVerifier.create(service.updateItems(TENANT, ORDER_ID, req))
                .expectError(ServiceValidationException.class)
                .verify();

        verify(router, never()).replace(eq(TENANT), eq("Sales Invoice"), eq(ORDER_ID), anyMap(), eq(SINGLE_TYPE));
    }

    // ---- ensureSubmitted ------------------------------------------------------

    @Test
    void ensureSubmitted_submits_draft_order() {
        ErpNextDoc draft = invoiceDoc(0, 1000.0);
        ErpNextDoc submitted = invoiceDoc(1, 1000.0);

        Map<String, Object> rawDraft = new HashMap<>();
        rawDraft.put("doctype", "Sales Invoice");
        rawDraft.put("name", ORDER_ID);
        rawDraft.put("customer", "Jane Doe");
        rawDraft.put("modified", "2024-01-01 10:00:00.000000");
        rawDraft.put("docstatus", 0);
        rawDraft.put("items", List.of(Map.of("item_code", ITEM_CODE, "qty", 10.0, "rate", 100.0)));

        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(draft)));
        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, RAW_SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(rawDraft)));
        when(router.callMethod(eq(TENANT), eq("frappe.client.submit"), anyMap(), eq(SUBMIT_TYPE)))
                .thenReturn(Mono.just(new ErpNextMessageResponse<>(submitted)));
        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, SINGLE_TYPE))
                .thenReturn(
                        Mono.just(new ErpNextSingleResponse<>(draft)),
                        Mono.just(new ErpNextSingleResponse<>(submitted)));

        StepVerifier.create(service.ensureSubmitted(TENANT, ORDER_ID, null))
                .verifyComplete();

        verify(router).callMethod(eq(TENANT), eq("frappe.client.submit"), anyMap(), eq(SUBMIT_TYPE));
    }

    @Test
    void ensureSubmitted_noop_when_already_submitted() {
        ErpNextDoc submitted = invoiceDoc(1, 1000.0);
        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(submitted)));

        StepVerifier.create(service.ensureSubmitted(TENANT, ORDER_ID, null))
                .verifyComplete();

        verify(router, never()).callMethod(any(), any(), anyMap(), any());
    }

    @Test
    void ensureSubmitted_rejects_cancelled_order() {
        ErpNextDoc cancelled = invoiceDoc(2, 1000.0);
        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(cancelled)));

        StepVerifier.create(service.ensureSubmitted(TENANT, ORDER_ID, null))
                .expectError(ConflictException.class)
                .verify();

        verify(router, never()).callMethod(any(), any(), anyMap(), any());
    }

    // ---- submitOrder ----------------------------------------------------------

    @Test
    void submitOrder_fetches_latest_doc_raw_then_submits_the_complete_doc_then_refetches_typed_in_order() {
        ErpNextDoc submitted = invoiceDoc(1, 1000.0);

        Map<String, Object> rawDraft = new HashMap<>();
        rawDraft.put("doctype", "Sales Invoice");
        rawDraft.put("name", ORDER_ID);
        rawDraft.put("customer", "Jane Doe");
        rawDraft.put("modified", "2024-01-01 10:00:00.000000");
        rawDraft.put("docstatus", 0);
        rawDraft.put("items", List.of(Map.of("item_code", ITEM_CODE, "qty", 10.0, "rate", 100.0)));

        AtomicReference<Map<String, Object>> capturedSubmitBody = new AtomicReference<>();
        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, RAW_SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(rawDraft)));
        when(router.getOne(TENANT, "Sales Invoice", ORDER_ID, SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(submitted)));
        when(router.callMethod(eq(TENANT), eq("frappe.client.submit"), anyMap(), eq(SUBMIT_TYPE)))
                .thenAnswer(inv -> {
                    capturedSubmitBody.set(inv.getArgument(2));
                    return Mono.just(new ErpNextMessageResponse<>(submitted));
                });

        StepVerifier.create(service.submitOrder(TENANT, ORDER_ID, null))
                .assertNext(resp -> {
                    assertThat(resp.orderId()).isEqualTo(ORDER_ID);
                    assertThat(resp.status()).isEqualTo("submitted");
                })
                .verifyComplete();

        // frappe.client.submit reconstructs its working doc purely from the "doc" payload it's
        // handed — frappe.get_doc on a plain dict populates an in-memory doc straight from that
        // dict's own keys, doing no DB load — so echoing back only a handful of fields (doctype,
        // name, modified, docstatus) leaves the rest (items, customer, ...) empty and fails
        // validate(). /submit must therefore fetch and echo back the COMPLETE current document,
        // which (being freshly fetched) also naturally satisfies check_if_latest's
        // optimistic-lock comparison on "modified".
        @SuppressWarnings("unchecked")
        Map<String, Object> submittedDoc = (Map<String, Object>) capturedSubmitBody.get().get("doc");
        assertThat(submittedDoc).isEqualTo(rawDraft);

        // recording payment (creating a Payment Entry against this invoice) is now strictly a
        // later, separate /pay step — ERPNext refuses to even insert a Payment Entry that
        // references a still-draft invoice, so /submit must finalise the invoice first
        InOrder order = inOrder(router);
        order.verify(router).getOne(TENANT, "Sales Invoice", ORDER_ID, RAW_SINGLE_TYPE);
        order.verify(router).callMethod(eq(TENANT), eq("frappe.client.submit"), anyMap(), eq(SUBMIT_TYPE));
        order.verify(router).getOne(TENANT, "Sales Invoice", ORDER_ID, SINGLE_TYPE);
    }
}
