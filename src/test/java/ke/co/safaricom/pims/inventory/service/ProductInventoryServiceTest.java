package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.CreateStockAdjustmentRequest;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.api.dto.StockAdjustmentResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import ke.co.safaricom.pims.inventory.mapper.InventoryMapper;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.service.ProductDraftMemoryStore;
import ke.co.safaricom.pims.inventory.web.service.ProductInventoryService;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductInventoryServiceTest {

    private static final String TENANT = "test-tenant";
    private static final String ITEM_CODE = "PIMS-ITEM-001";

    @Mock
    private ErpNextTenantRouter router;
    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private InventoryService inventoryService;

    private ProductDraftMemoryStore drafts;
    private ProductInventoryService service;

    @BeforeEach
    void setUp() {
        drafts = new ProductDraftMemoryStore();
        service = new ProductInventoryService(router, inventoryMapper, inventoryService, drafts);
    }

    // ---- helpers ----------------------------------------------------------------

    private InventoryItemResponse item(String id) {
        return new InventoryItemResponse(id, "Amoxicillin 500mg", "", "Antibiotics",
                false, false, null, 50.0, 500.0, "Nos", List.of(), Map.of());
    }

    private BatchResponse batch(String id, String itemCode, double qty, String expiry) {
        return new BatchResponse(id, "BATCH-" + id, itemCode, qty, "available",
                expiry, null, "Main Warehouse", null, "2024-01-01", 10.0, "Supplier A", null);
    }

    private StockAdjustmentResponse adjustment(String id, String type, double qty, String product) {
        return new StockAdjustmentResponse(id, id, product, type, qty, "test reason", "2024-01-15", "user@test.com");
    }

    @SuppressWarnings("unchecked")
    private <T> T singleResponse(ErpNextDoc doc) {
        ErpNextSingleResponse<ErpNextDoc> resp = new ErpNextSingleResponse<>(doc);
        return (T) resp;
    }

    private ErpNextDoc itemDoc(String name) {
        // 51 fields: name,owner,creation,modified,docstatus(5), itemName,itemGroup,stockUom,description,disabled,isStockItem,reorderLevels(7),
        // customPims* x11 String(13-23), Double x2(24-25), Integer+String+Integer+Integer+Integer+String(26-31), itemCode(32),
        // warehouse,actualQty,reservedQty(33-35), batchId,expiryDate,mfgDate,supplier(36-39),
        // stockEntryType,purpose,postingDate,remarks,fromWarehouse,toWarehouse,items(40-46), status,txDate,schedDate,grandTotal,itemsCount(47-51)
        return new ErpNextDoc(name, null, null, null, null,
                "Amoxicillin 500mg", "Antibiotics", "Nos", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                name,
                null, null, null,
                null,              // item (Batch parent link)
                null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private void stubListItemsAndBatches(String itemCode, double qty) {
        InventoryItemResponse itemResp = item(itemCode);
        BatchResponse batchResp = batch("b1", itemCode, qty, "2026-12-31");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(batchResp)));
        when(inventoryMapper.copyWithBatches(eq(itemResp), anyList())).thenReturn(itemResp);
    }

    // ---- listProducts -----------------------------------------------------------

    @Test
    void listProducts_returns_paginated_list() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, null, null))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(1);
                    assertThat(resp.pagination().page()).isEqualTo(1);
                    assertThat(resp.pagination().limit()).isEqualTo(10);
                    assertThat(resp.pagination().total()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void listProducts_search_filters_by_product_name() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, "Amoxicillin", null, null, null))
                .assertNext(resp -> assertThat(resp.data()).hasSize(1))
                .verifyComplete();
    }

    @Test
    void listProducts_search_no_match_returns_empty() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, "Ibuprofen", null, null, null))
                .assertNext(resp -> assertThat(resp.data()).isEmpty())
                .verifyComplete();
    }

    @Test
    void listProducts_category_filter_excludes_non_matching() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, Enums.ProductCategory.Analgesics, null, null))
                .assertNext(resp -> assertThat(resp.data()).isEmpty())
                .verifyComplete();
    }

    @Test
    void listProducts_category_filter_includes_matching() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, Enums.ProductCategory.Antibiotics, null, null))
                .assertNext(resp -> assertThat(resp.data()).hasSize(1))
                .verifyComplete();
    }

    @Test
    void listProducts_status_filter_available_includes_well_stocked() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, Enums.ProductStatus.available, null))
                .assertNext(resp -> assertThat(resp.data()).hasSize(1))
                .verifyComplete();
    }

    @Test
    void listProducts_status_filter_out_of_stock_excludes_stocked_item() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, Enums.ProductStatus.out_of_stock, null))
                .assertNext(resp -> assertThat(resp.data()).isEmpty())
                .verifyComplete();
    }

    @Test
    void listProducts_manufacturer_filter_excludes_non_matching() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);
        UUID unknownMfrId = UUID.randomUUID();

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, null, unknownMfrId))
                .assertNext(resp -> assertThat(resp.data()).isEmpty())
                .verifyComplete();
    }

    @Test
    void listProducts_respects_pagination_page2() {
        InventoryItemResponse itemA = new InventoryItemResponse("ITEM-A", "Alpha", "", "Antibiotics",
                false, false, null, 0, 0, "Nos", List.of(), Map.of());
        InventoryItemResponse itemB = new InventoryItemResponse("ITEM-B", "Beta", "", "Antibiotics",
                false, false, null, 0, 0, "Nos", List.of(), Map.of());
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemA, itemB)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of()));
        when(inventoryMapper.copyWithBatches(eq(itemA), anyList())).thenReturn(itemA);
        when(inventoryMapper.copyWithBatches(eq(itemB), anyList())).thenReturn(itemB);

        StepVerifier.create(service.listProducts(TENANT, 2, 1, null, null, null, null))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(1);
                    assertThat(resp.pagination().page()).isEqualTo(2);
                    assertThat(resp.pagination().total()).isEqualTo(2);
                    assertThat(resp.pagination().totalPages()).isEqualTo(2);
                })
                .verifyComplete();
    }

    // ---- createProduct ----------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void createProduct_success_creates_item_and_returns_detail() {
        InventoryApiSchemas.CreateProductRequest req = new InventoryApiSchemas.CreateProductRequest(
                "Amoxicillin 500mg", "Amoxicillin", null, Enums.ProductCategory.Antibiotics,
                "PPB-001", null, null, "500mg", "Capsules", null, null, null,
                Enums.UnitOfMeasure.capsules, 50.0, 500.0, null, null);

        // Capture the randomly-generated itemCode so we can return the right item from listItems
        java.util.concurrent.atomic.AtomicReference<String> capturedCode = new java.util.concurrent.atomic.AtomicReference<>();
        when(inventoryService.listItems(TENANT))
                .thenAnswer(inv -> {
                    String code = capturedCode.get();
                    if (code == null) return Mono.just(List.of());
                    return Mono.just(List.of(item(code)));
                });
        when(router.create(eq(TENANT), eq("Item"), anyMap(), any(Class.class)))
                .thenAnswer(inv -> {
                    Map<String, Object> body = inv.getArgument(2);
                    String code = (String) body.get("item_code");
                    capturedCode.set(code);
                    return Mono.just(singleResponse(itemDoc(code)));
                });
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of()));
        when(inventoryMapper.copyWithBatches(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        StepVerifier.create(service.createProduct(TENANT, req))
                .assertNext(detail -> {
                    assertThat(detail).isNotNull();
                    assertThat(detail.productName()).isEqualTo("Amoxicillin 500mg");
                })
                .verifyComplete();
    }

    @Test
    void createProduct_throws_conflict_on_duplicate_ppb_code() {
        InventoryItemResponse existing = new InventoryItemResponse(
                ITEM_CODE, "Amoxicillin 500mg", "<<<PIMS_ITEM_EXTRAS>>>{ppb_code:PPB-001}<<<END_PIMS_ITEM_EXTRAS>>>",
                "Antibiotics", false, false, null, 50.0, 500.0, "Nos", List.of(), Map.of("ppb_code", "PPB-001"));
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(existing)));

        InventoryApiSchemas.CreateProductRequest req = new InventoryApiSchemas.CreateProductRequest(
                "Another Drug", "Drug", null, Enums.ProductCategory.Antibiotics,
                "PPB-001", null, null, null, null, null, null, null,
                Enums.UnitOfMeasure.tablets, 10.0, 100.0, null, null);

        StepVerifier.create(service.createProduct(TENANT, req))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    void createProduct_throws_validation_error_when_product_name_missing() {
        InventoryApiSchemas.CreateProductRequest req = new InventoryApiSchemas.CreateProductRequest(
                "", "Generic", null, Enums.ProductCategory.Antibiotics,
                null, null, null, null, null, null, null, null,
                Enums.UnitOfMeasure.tablets, 10.0, 100.0, null, null);

        StepVerifier.create(Mono.defer(() -> service.createProduct(TENANT, req)))
                .expectError(ServiceValidationException.class)
                .verify();
    }

    @Test
    void createProduct_throws_validation_error_when_category_missing() {
        InventoryApiSchemas.CreateProductRequest req = new InventoryApiSchemas.CreateProductRequest(
                "Drug", "Generic", null, null,
                null, null, null, null, null, null, null, null,
                Enums.UnitOfMeasure.tablets, 10.0, 100.0, null, null);

        StepVerifier.create(Mono.defer(() -> service.createProduct(TENANT, req)))
                .expectError(ServiceValidationException.class)
                .verify();
    }

    // ---- Draft lifecycle --------------------------------------------------------

    @Test
    void saveDraft_returns_draft_with_assigned_id() {
        InventoryApiSchemas.ProductDraftRequest req = new InventoryApiSchemas.ProductDraftRequest(
                Enums.WizardStep.ONE, null);

        StepVerifier.create(service.saveDraft(TENANT, req))
                .assertNext(draft -> {
                    assertThat(draft.id()).isNotNull();
                    assertThat(draft.wizardStep()).isEqualTo(Enums.WizardStep.ONE);
                })
                .verifyComplete();
    }

    @Test
    void getDraft_returns_previously_saved_draft() {
        InventoryApiSchemas.ProductDraftRequest req = new InventoryApiSchemas.ProductDraftRequest(
                Enums.WizardStep.TWO, null);

        InventoryApiSchemas.ProductDraft saved = drafts.create(TENANT, req);

        StepVerifier.create(service.getDraft(TENANT, saved.id()))
                .assertNext(d -> assertThat(d.id()).isEqualTo(saved.id()))
                .verifyComplete();
    }

    @Test
    void getDraft_throws_not_found_for_unknown_id() {
        StepVerifier.create(service.getDraft(TENANT, UUID.randomUUID()))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    void patchDraft_updates_wizard_step_and_data() {
        InventoryApiSchemas.ProductDraftRequest initial = new InventoryApiSchemas.ProductDraftRequest(
                Enums.WizardStep.ONE, null);
        InventoryApiSchemas.ProductDraft saved = drafts.create(TENANT, initial);

        InventoryApiSchemas.CreateProductRequest newData = new InventoryApiSchemas.CreateProductRequest(
                "Amoxicillin", null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        InventoryApiSchemas.ProductDraftRequest patch = new InventoryApiSchemas.ProductDraftRequest(
                Enums.WizardStep.TWO, newData);

        StepVerifier.create(service.patchDraft(TENANT, saved.id(), patch))
                .assertNext(d -> {
                    assertThat(d.wizardStep()).isEqualTo(Enums.WizardStep.TWO);
                    assertThat(d.data().productName()).isEqualTo("Amoxicillin");
                })
                .verifyComplete();
    }

    // ---- getProduct -------------------------------------------------------------

    @Test
    void getProduct_returns_product_detail_with_batches() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);
        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.getProduct(TENANT, productId))
                .assertNext(detail -> {
                    assertThat(detail.id()).isEqualTo(productId);
                    assertThat(detail.batches()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void getProduct_throws_not_found_for_unknown_product_id() {
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of()));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.getProduct(TENANT, UUID.randomUUID()))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ---- deleteProduct ----------------------------------------------------------

    @Test
    void deleteProduct_calls_router_delete() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(router.delete(eq(TENANT), eq("Item"), eq(ITEM_CODE))).thenReturn(Mono.empty());

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.deleteProduct(TENANT, productId))
                .verifyComplete();

        verify(router).delete(TENANT, "Item", ITEM_CODE);
    }

    @Test
    void deleteProduct_throws_not_found_for_unknown_product() {
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.deleteProduct(TENANT, UUID.randomUUID()))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ---- listBatches ------------------------------------------------------------

    @Test
    void listBatches_returns_paginated_batches_for_product() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        BatchResponse b1 = batch("b1", ITEM_CODE, 50.0, "2026-06-30");
        BatchResponse b2 = batch("b2", ITEM_CODE, 30.0, "2025-12-31");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(b1, b2)));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.listBatches(TENANT, productId, 1, 10, null, null))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(2);
                    assertThat(resp.pagination().total()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    void listBatches_fefo_sort_orders_by_expiry_ascending() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        BatchResponse b1 = batch("b1", ITEM_CODE, 50.0, "2026-06-30");
        BatchResponse b2 = batch("b2", ITEM_CODE, 30.0, "2025-03-15");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(b1, b2)));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.listBatches(TENANT, productId, 1, 10, null, "fefo"))
                .assertNext(resp -> {
                    assertThat(resp.data().get(0).expiryDate()).isEqualTo("2025-03-15");
                    assertThat(resp.data().get(1).expiryDate()).isEqualTo("2026-06-30");
                })
                .verifyComplete();
    }

    @Test
    void listBatches_lifo_sort_orders_by_expiry_descending() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        BatchResponse b1 = batch("b1", ITEM_CODE, 50.0, "2026-06-30");
        BatchResponse b2 = batch("b2", ITEM_CODE, 30.0, "2025-03-15");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(b1, b2)));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.listBatches(TENANT, productId, 1, 10, null, "lifo"))
                .assertNext(resp -> {
                    assertThat(resp.data().get(0).expiryDate()).isEqualTo("2026-06-30");
                    assertThat(resp.data().get(1).expiryDate()).isEqualTo("2025-03-15");
                })
                .verifyComplete();
    }

    @Test
    void listBatches_status_filter_excludes_non_matching() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        BatchResponse b1 = batch("b1", ITEM_CODE, 50.0, "2026-06-30");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(b1)));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.listBatches(TENANT, productId, 1, 10, Enums.BatchStatus.expired, null))
                .assertNext(resp -> assertThat(resp.data()).isEmpty())
                .verifyComplete();
    }

    // ---- getBatch / deleteBatch -------------------------------------------------

    @Test
    void getBatch_returns_batch_for_known_ids() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        BatchResponse b1 = batch("b1", ITEM_CODE, 50.0, "2026-06-30");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(b1)));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        UUID batchId = StableEntityIds.batchId(TENANT, "b1");

        StepVerifier.create(service.getBatch(TENANT, productId, batchId))
                .assertNext(b -> assertThat(b.id()).isEqualTo(batchId))
                .verifyComplete();
    }

    @Test
    void getBatch_throws_not_found_for_unknown_batch_id() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of()));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.getBatch(TENANT, productId, UUID.randomUUID()))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    void deleteBatch_calls_router_delete_on_erp_batch_name() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        BatchResponse b1 = batch("erp-batch-1", ITEM_CODE, 50.0, "2026-06-30");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(b1)));
        when(router.delete(eq(TENANT), eq("Batch"), eq("erp-batch-1"))).thenReturn(Mono.empty());

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        UUID batchId = StableEntityIds.batchId(TENANT, "erp-batch-1");

        StepVerifier.create(service.deleteBatch(TENANT, productId, batchId))
                .verifyComplete();

        verify(router).delete(TENANT, "Batch", "erp-batch-1");
    }

    // ---- addBatchJsonReturn -----------------------------------------------------

    @Test
    void addBatchJsonReturn_creates_batch_and_returns_it() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        // batchNumber must match req.batchNumber() so lastCreatedBatchForItem finds it
        BatchResponse createdBatch = new BatchResponse(
                "erp-b99", "BATCH-99", ITEM_CODE, 100.0, "available",
                "2027-01-01", null, "Main Warehouse", null, "2024-01-01", 10.0, "Supplier A", null);
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(router.create(eq(TENANT), eq("Batch"), anyMap(), any(Class.class)))
                .thenReturn(Mono.just(singleResponse(itemDoc("erp-b99"))));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(createdBatch)));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        InventoryApiSchemas.CreateBatchRequest req = new InventoryApiSchemas.CreateBatchRequest(
                "BATCH-99", "2027-01-01", "Supplier A", 100.0, null, 10.0, "Main Warehouse", null, null);

        StepVerifier.create(service.addBatchJsonReturn(TENANT, productId, req))
                .assertNext(b -> assertThat(b.batchNumber()).isEqualTo("BATCH-99"))
                .verifyComplete();
    }

    @Test
    void addBatchJsonReturn_throws_when_batch_number_missing() {
        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        InventoryApiSchemas.CreateBatchRequest req = new InventoryApiSchemas.CreateBatchRequest(
                null, "2027-01-01", "Supplier A", 100.0, null, 10.0, "Main Warehouse", null, null);

        StepVerifier.create(Mono.defer(() -> service.addBatchJsonReturn(TENANT, productId, req)))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ---- ingestCsvLines ---------------------------------------------------------

    @Test
    void ingestCsvLines_empty_input_returns_zero_result() {
        StepVerifier.create(service.ingestCsvLines(TENANT, ITEM_CODE, List.of()))
                .assertNext(result -> {
                    InventoryApiSchemas.BatchBulkUploadResult r = (InventoryApiSchemas.BatchBulkUploadResult) result;
                    assertThat(r.totalRows()).isEqualTo(0);
                    assertThat(r.successCount()).isEqualTo(0);
                    assertThat(r.errorCount()).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    void ingestCsvLines_row_with_insufficient_columns_counts_as_error() {
        List<String[]> badRows = List.<String[]>of(new String[]{"BATCH-001", "2027-01-01"});

        StepVerifier.create(service.ingestCsvLines(TENANT, ITEM_CODE, badRows))
                .assertNext(result -> {
                    InventoryApiSchemas.BatchBulkUploadResult r = (InventoryApiSchemas.BatchBulkUploadResult) result;
                    assertThat(r.totalRows()).isEqualTo(1);
                    assertThat(r.errorCount()).isEqualTo(1);
                    assertThat(r.successCount()).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    void ingestCsvLines_valid_rows_count_as_success() {
        // batchNumber must match cells[0] so lastCreatedBatchForItem can find it
        BatchResponse createdBatch = new BatchResponse(
                "erp-csv-1", "BATCH-csv-1", ITEM_CODE, 50.0, "available",
                "2027-06-01", null, "Main Warehouse", null, "2024-01-01", 12.5, null, null);
        when(router.create(eq(TENANT), eq("Batch"), anyMap(), any(Class.class)))
                .thenReturn(Mono.just(singleResponse(itemDoc(ITEM_CODE))));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(createdBatch)));

        List<String[]> rows = List.<String[]>of(
                new String[]{"BATCH-csv-1", "2027-06-01", "50", "12.5", "Main Warehouse"}
        );

        StepVerifier.create(service.ingestCsvLines(TENANT, ITEM_CODE, rows))
                .assertNext(result -> {
                    InventoryApiSchemas.BatchBulkUploadResult r = (InventoryApiSchemas.BatchBulkUploadResult) result;
                    assertThat(r.totalRows()).isEqualTo(1);
                    assertThat(r.successCount()).isEqualTo(1);
                    assertThat(r.errorCount()).isEqualTo(0);
                })
                .verifyComplete();
    }

    // ---- listAdjustments --------------------------------------------------------

    @Test
    void listAdjustments_returns_adjustments_for_product() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        StockAdjustmentResponse adj = adjustment("adj-1", "addition", 10.0, ITEM_CODE);
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listAdjustments(ArgumentMatchers.eq(TENANT), ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(List.of(adj)));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.listAdjustments(TENANT, productId, 1, 10))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(1);
                    assertThat(resp.data().get(0).adjustmentType()).isEqualTo(Enums.AdjustmentDirection.increase);
                })
                .verifyComplete();
    }

    @Test
    void listAdjustments_filters_to_matching_product_only() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        // server-side filter (linked child) returns empty when no entries match this item
        when(inventoryService.listAdjustments(ArgumentMatchers.eq(TENANT), ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(List.of()));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.listAdjustments(TENANT, productId, 1, 10))
                .assertNext(resp -> assertThat(resp.data()).isEmpty())
                .verifyComplete();
    }

    // ---- adjustStock ------------------------------------------------------------

    @Test
    void adjustStock_increase_maps_to_addition_and_returns_adjustment() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        BatchResponse batchResp = batch("erp-b1", ITEM_CODE, 50.0, "2026-12-31");
        StockAdjustmentResponse adjResp = adjustment("adj-new", "addition", 10.0, ITEM_CODE);
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(batchResp)));
        when(inventoryService.createAdjustment(eq(TENANT), any(CreateStockAdjustmentRequest.class)))
                .thenReturn(Mono.just(adjResp));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        UUID batchId = StableEntityIds.batchId(TENANT, "erp-b1");

        InventoryApiSchemas.StockAdjustmentRequest req = new InventoryApiSchemas.StockAdjustmentRequest(
                batchId, Enums.AdjustmentDirection.increase, 10.0, Enums.AdjustmentReason.correction, null, null);

        StepVerifier.create(service.adjustStock(TENANT, productId, req, "user@test.com", "Test User"))
                .assertNext(adj -> {
                    assertThat(adj.adjustmentType()).isEqualTo(Enums.AdjustmentDirection.increase);
                    assertThat(adj.quantity()).isEqualTo(10.0);
                    assertThat(adj.quantityBefore()).isEqualTo(50.0);
                    assertThat(adj.quantityAfter()).isEqualTo(60.0);
                })
                .verifyComplete();
    }

    @Test
    void adjustStock_decrease_on_empty_batch_throws_validation_exception() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        BatchResponse emptyBatch = batch("erp-b1", ITEM_CODE, 0.0, "2026-12-31");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(emptyBatch)));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        UUID batchId = StableEntityIds.batchId(TENANT, "erp-b1");

        InventoryApiSchemas.StockAdjustmentRequest req = new InventoryApiSchemas.StockAdjustmentRequest(
                batchId, Enums.AdjustmentDirection.decrease, 5.0, Enums.AdjustmentReason.damaged, null, null);

        StepVerifier.create(service.adjustStock(TENANT, productId, req, "user@test.com", "Test User"))
                .expectError(ServiceValidationException.class)
                .verify();
    }

    // ---- manufacturers / terminology --------------------------------------------

    @Test
    void manufacturers_returns_filtered_list() {
        StepVerifier.create(service.manufacturers("Teva", 5))
                .assertNext(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.get(0).name()).isEqualTo("Teva Pharmaceuticals");
                })
                .verifyComplete();
    }

    @Test
    void manufacturers_returns_all_when_no_filter() {
        StepVerifier.create(service.manufacturers(null, null))
                .assertNext(list -> assertThat(list).hasSize(5))
                .verifyComplete();
    }

    @Test
    void searchTerminology_returns_results_for_known_query() {
        StepVerifier.create(service.searchTerminology("amoxicillin", null, Enums.TerminologySource.all, 10))
                .assertNext(resp -> assertThat(resp.data()).isNotEmpty())
                .verifyComplete();
    }

    @Test
    void terminologyProduct_returns_product_for_known_id() {
        StepVerifier.create(service.terminologyProduct("rxn-1001"))
                .assertNext(p -> assertThat(p.terminologyId()).isEqualTo("rxn-1001"))
                .verifyComplete();
    }

    @Test
    void terminologyProduct_throws_not_found_for_unknown_id() {
        StepVerifier.create(service.terminologyProduct("unknown-id"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }
}
