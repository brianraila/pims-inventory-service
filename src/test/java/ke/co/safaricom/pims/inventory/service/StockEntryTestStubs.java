package ke.co.safaricom.pims.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextMessageResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Shared ERPNext Stock Entry mocks for fast unit tests. */
final class StockEntryTestStubs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<ErpNextSingleResponse<Map<String, Object>>> RAW_SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<ErpNextMessageResponse<ErpNextDoc>> SUBMIT_TYPE =
            new ParameterizedTypeReference<>() {};

    private StockEntryTestStubs() {}

    static ErpNextDoc doc(String name, int docstatus) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("docstatus", docstatus);
        return MAPPER.convertValue(fields, ErpNextDoc.class);
    }

    /** Lenient default: any Stock Entry create/submit succeeds (batch add, CSV ingest, etc.). */
    static void lenientBatchStockFlow(ErpNextTenantRouter router, String tenant) {
        lenient().when(router.create(eq(tenant), eq("Stock Entry"), anyMap(), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(doc("STE-DEFAULT", 0))));
        stubSubmit(router, tenant, "STE-DEFAULT", "Material Receipt", "Material Receipt");
    }

    static void stubMaterialIssueReversal(
            ErpNextTenantRouter router, String tenant, String entryName, String itemCode, double qty) {
        when(router.create(eq(tenant), eq("Stock Entry"), anyMap(), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(doc(entryName, 0))));
        stubSubmit(router, tenant, entryName, "Material Issue", "Material Issue");
    }

    private static void stubSubmit(
            ErpNextTenantRouter router,
            String tenant,
            String entryName,
            String entryType,
            String purpose) {
        Map<String, Object> rawLatest = new HashMap<>();
        rawLatest.put("doctype", "Stock Entry");
        rawLatest.put("name", entryName);
        rawLatest.put("stock_entry_type", entryType);
        rawLatest.put("purpose", purpose);
        rawLatest.put("modified", "2026-06-06 10:00:00.000000");
        rawLatest.put("docstatus", 0);
        rawLatest.put("items", List.of());

        lenient().when(router.getOne(tenant, "Stock Entry", entryName, RAW_SINGLE_TYPE))
                .thenReturn(Mono.just(new ErpNextSingleResponse<>(rawLatest)));
        lenient().when(router.callMethod(eq(tenant), eq("frappe.client.submit"), anyMap(), eq(SUBMIT_TYPE)))
                .thenReturn(Mono.just(new ErpNextMessageResponse<>(doc(entryName, 1))));
    }
}
