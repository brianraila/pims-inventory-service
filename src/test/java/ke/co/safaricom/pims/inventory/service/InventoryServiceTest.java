package ke.co.safaricom.pims.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ke.co.safaricom.pims.inventory.api.dto.CreateStockAdjustmentRequest;
import ke.co.safaricom.pims.inventory.api.dto.StockAdjustmentResponse;
import ke.co.safaricom.pims.inventory.config.ErpNextProperties;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.mapper.InventoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final String TENANT = "test-tenant";
    private static final String WAREHOUSE = "Main Warehouse";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextSingleResponse<Map<String, Object>>> RAW_SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErpNextMessageResponse<ErpNextDoc>> SUBMIT_TYPE =
            new ParameterizedTypeReference<>() {};

    @Mock
    private ErpNextTenantRouter router;
    @Mock
    private InventoryMapper mapper;

    private InventoryService service;

    @BeforeEach
    void setUp() {
        ErpNextProperties properties = new ErpNextProperties(null, null, null, null, null, WAREHOUSE, null, null);
        service = new InventoryService(router, mapper, properties);
    }

    private static ErpNextDoc binDoc(String itemCode, Double actualQty, Double reservedQty) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("item_code", itemCode);
        fields.put("actual_qty", actualQty);
        fields.put("reserved_qty", reservedQty);
        return MAPPER.convertValue(fields, ErpNextDoc.class);
    }

    private static ErpNextDoc stockEntryDoc(String name, int docstatus) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("docstatus", docstatus);
        return MAPPER.convertValue(fields, ErpNextDoc.class);
    }

    @Test
    void getStockLevels_computes_available_as_actual_minus_reserved() {
        when(router.getList(eq(TENANT), eq("Bin"), anyMap(), eq(LIST_TYPE)))
                .thenReturn(Mono.just(new ErpNextListResponse<>(List.of(
                        binDoc("PIMS-ITEM-001", 100.0, 30.0),
                        binDoc("PIMS-ITEM-002", 50.0, null)))));

        StepVerifier.create(service.getStockLevels(TENANT, List.of("PIMS-ITEM-001", "PIMS-ITEM-002"), WAREHOUSE))
                .assertNext(levels -> assertThat(levels)
                        .containsEntry("PIMS-ITEM-001", 70.0)
                        .containsEntry("PIMS-ITEM-002", 50.0))
                .verifyComplete();
    }

    @Test
    void getStockLevels_returns_empty_map_without_querying_when_no_item_codes() {
        StepVerifier.create(service.getStockLevels(TENANT, List.of(), WAREHOUSE))
                .assertNext(levels -> assertThat(levels).isEmpty())
                .verifyComplete();

        verifyNoInteractions(router);
    }

    // ---- createAdjustment ----------------------------------------------------

    @Test
    void createAdjustment_creates_then_submits_the_stock_entry_so_quantity_is_actually_booked() {
        CreateStockAdjustmentRequest request = new CreateStockAdjustmentRequest(
                "PIMS-ITEM-001", WAREHOUSE, 10.0, "addition", "Correction", null);
        StockAdjustmentResponse mapped = new StockAdjustmentResponse(
                "STE-0001", "STE-0001", "PIMS-ITEM-001", "addition", 10.0, "Correction", "2026-06-06", "user@test.com");

        Map<String, Object> rawLatest = new HashMap<>();
        rawLatest.put("doctype", "Stock Entry");
        rawLatest.put("name", "STE-0001");
        rawLatest.put("stock_entry_type", "Material Receipt");
        rawLatest.put("purpose", "Material Receipt");
        rawLatest.put("modified", "2026-06-06 10:00:00.000000");
        rawLatest.put("docstatus", 0);
        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("item_code", "PIMS-ITEM-001");
        lineItem.put("qty", 10.0);
        rawLatest.put("items", new java.util.ArrayList<>(List.of(lineItem)));

        when(router.create(eq(TENANT), eq("Stock Entry"), anyMap(), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(stockEntryDoc("STE-0001", 0))));
        when(router.getOne(TENANT, "Stock Entry", "STE-0001", RAW_SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(rawLatest)));

        AtomicReference<Map<String, Object>> capturedSubmitBody = new AtomicReference<>();
        when(router.callMethod(eq(TENANT), eq("frappe.client.submit"), anyMap(), eq(SUBMIT_TYPE)))
                .thenAnswer(inv -> {
                    capturedSubmitBody.set(inv.getArgument(2));
                    return Mono.just(new ErpNextMessageResponse<>(stockEntryDoc("STE-0001", 1)));
                });
        when(mapper.toAdjustmentResponse(any(ErpNextDoc.class))).thenReturn(mapped);

        StepVerifier.create(service.createAdjustment(TENANT, request))
                .assertNext(resp -> assertThat(resp.id()).isEqualTo("STE-0001"))
                .verifyComplete();

        // frappe.client.submit reconstructs its working doc purely from the "doc" payload —
        // frappe.get_doc on a dict populates an in-memory doc straight from that dict's own
        // keys with no DB load, so a partial {doctype, name} echo leaves required fields like
        // "purpose"/"items" empty and fails validate() with e.g. "Purpose must be one of ...".
        // So createAdjustment must re-fetch the full current document and echo it back whole.
        @SuppressWarnings("unchecked")
        Map<String, Object> submittedDoc = (Map<String, Object>) capturedSubmitBody.get().get("doc");
        assertThat(submittedDoc).isEqualTo(rawLatest);

        verify(mapper).toAdjustmentResponse(argThat(doc -> doc.docstatus() != null && doc.docstatus() == 1));
    }
}
