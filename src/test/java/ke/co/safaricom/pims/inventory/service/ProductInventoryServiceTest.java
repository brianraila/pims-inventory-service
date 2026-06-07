package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.CreateStockAdjustmentRequest;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.api.dto.StockAdjustmentResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ke.co.safaricom.pims.inventory.service.StockEntryTestStubs.SINGLE_TYPE;
import static ke.co.safaricom.pims.inventory.service.StockEntryTestStubs.stubMaterialIssueReversal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductInventoryServiceTest {

    private static final String TENANT = "test-tenant";
    private static final String ITEM_CODE = "PIMS-ITEM-001";
    private static final String WAREHOUSE = "Main Warehouse";

    @Mock
    private ErpNextTenantRouter router;
    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private CategoryService categoryService;

    private ProductDraftMemoryStore drafts;
    private ProductInventoryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        drafts = new ProductDraftMemoryStore();
        service = new ProductInventoryService(router, inventoryMapper, inventoryService, categoryService, drafts);
        when(categoryService.validateLeafCategoryExists(anyString(), anyString())).thenReturn(Mono.empty());
        // Default: no Sales Invoice Items available — order frequency falls back to alphabetical
        lenient().doReturn(Mono.just(new ErpNextListResponse<>(List.<Map<String, Object>>of())))
                .when(router).getList(anyString(), eq("Sales Invoice Item"), anyMap(),
                        any(org.springframework.core.ParameterizedTypeReference.class));
        lenient().when(inventoryService.getSellingPrices(anyString(), anyList()))
                .thenReturn(Mono.just(Map.of()));
        StockEntryTestStubs.lenientBatchStockFlow(router, TENANT);
    }

    // ---- helpers ----------------------------------------------------------------

    private InventoryItemResponse item(String id) {
        return new InventoryItemResponse(id, "Amoxicillin 500mg", "", "Antibiotics",
                false, false, null, 50.0, 500.0, "Nos", List.of(), Map.of());
    }

    private BatchResponse batch(String id, String itemCode, double qty, String expiry) {
        return new BatchResponse(id, "BATCH-" + id, itemCode, qty, "available",
                expiry, null, "Main Warehouse", null, "2024-01-01", 10.0, 0.0, "Supplier A", null);
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
                null,              // priceListRate (Item Price)
                name,
                null, null, null,
                null,              // item (Batch parent link)
                null, null, null, null,
                null, null, null, null, null, null, null, null,  // batchQty, customPimsUnitCost, customPimsTradeCost, supplierName, warehouseName, itemGroupName, parentItemGroup, isGroup
                null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,   // customer, currency, netTotal, totalTaxesAndCharges
                null, null, null, null, null, null, null);  // isPos, paidAmount, outstandingAmount, modeOfPayment, referenceNo, referenceDate, party
    }

    private void stubListItemsAndBatches(String itemCode, double qty) {
        InventoryItemResponse itemResp = item(itemCode);
        BatchResponse batchResp = batch("b1", itemCode, qty, "2026-12-31");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(batchResp)));
        when(inventoryMapper.copyWithBatches(eq(itemResp), anyList())).thenReturn(itemResp);
        when(inventoryService.defaultWarehouse()).thenReturn(WAREHOUSE);
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE)))
                .thenReturn(Mono.just(Map.of(itemCode, qty - 10.0)));
    }

    // ---- listProducts -----------------------------------------------------------

    @Test
    void listProducts_returns_paginated_list() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, null, null, "most_ordered"))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(1);
                    assertThat(resp.pagination().page()).isEqualTo(1);
                    assertThat(resp.pagination().limit()).isEqualTo(10);
                    assertThat(resp.pagination().total()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void listProducts_includes_available_quantity_distinct_from_total_stock() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, null, null, "most_ordered"))
                .assertNext(resp -> {
                    InventoryApiSchemas.ProductSummary summary = resp.data().get(0);
                    assertThat(summary.totalStock()).isEqualTo(100.0);
                    assertThat(summary.availableQuantity()).isEqualTo(90.0);
                    assertThat(summary.availableQuantity()).isNotEqualTo(summary.totalStock());
                })
                .verifyComplete();
    }

    @Test
    void listProducts_search_filters_by_product_name() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, "Amoxicillin", null, null, null, "most_ordered"))
                .assertNext(resp -> assertThat(resp.data()).hasSize(1))
                .verifyComplete();
    }

    @Test
    void listProducts_search_no_match_returns_empty() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, "Ibuprofen", null, null, null, "most_ordered"))
                .assertNext(resp -> assertThat(resp.data()).isEmpty())
                .verifyComplete();
    }

    @Test
    void listProducts_category_filter_excludes_non_matching() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, "Analgesics", null, null, "most_ordered"))
                .assertNext(resp -> assertThat(resp.data()).isEmpty())
                .verifyComplete();
    }

    @Test
    void listProducts_category_filter_includes_matching() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, "Antibiotics", null, null, "most_ordered"))
                .assertNext(resp -> assertThat(resp.data()).hasSize(1))
                .verifyComplete();
    }

    @Test
    void listProducts_status_filter_available_includes_well_stocked() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, Enums.ProductStatus.available, null, "most_ordered"))
                .assertNext(resp -> assertThat(resp.data()).hasSize(1))
                .verifyComplete();
    }

    @Test
    void listProducts_status_filter_out_of_stock_excludes_stocked_item() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, Enums.ProductStatus.out_of_stock, null, "most_ordered"))
                .assertNext(resp -> assertThat(resp.data()).isEmpty())
                .verifyComplete();
    }

    @Test
    void listProducts_manufacturer_filter_excludes_non_matching() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);
        UUID unknownMfrId = UUID.randomUUID();

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, null, unknownMfrId, "most_ordered"))
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
        when(inventoryService.defaultWarehouse()).thenReturn(WAREHOUSE);
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE))).thenReturn(Mono.just(Map.of()));

        StepVerifier.create(service.listProducts(TENANT, 2, 1, null, null, null, null, "most_ordered"))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(1);
                    assertThat(resp.pagination().page()).isEqualTo(2);
                    assertThat(resp.pagination().total()).isEqualTo(2);
                    assertThat(resp.pagination().totalPages()).isEqualTo(2);
                })
                .verifyComplete();
    }

    // ---- sort: most_ordered / alphabetical --------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void listProducts_most_ordered_sort_places_highest_frequency_item_first() {
        InventoryItemResponse itemA = new InventoryItemResponse(
                "ITEM-A", "Alpha Drug", "", "Antibiotics",
                false, false, null, 0, 0, "Nos", List.of(), Map.of());
        InventoryItemResponse itemB = new InventoryItemResponse(
                "ITEM-B", "Beta Drug", "", "Antibiotics",
                false, false, null, 0, 0, "Nos", List.of(), Map.of());
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemA, itemB)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of()));
        when(inventoryMapper.copyWithBatches(eq(itemA), anyList())).thenReturn(itemA);
        when(inventoryMapper.copyWithBatches(eq(itemB), anyList())).thenReturn(itemB);
        when(inventoryService.defaultWarehouse()).thenReturn(WAREHOUSE);
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE))).thenReturn(Mono.just(Map.of()));

        // ITEM-B ordered more (qty=200) vs ITEM-A (qty=30)
        List<Map<String, Object>> siItems = List.of(
                Map.of("item_code", "ITEM-A", "qty", 30.0),
                Map.of("item_code", "ITEM-B", "qty", 200.0)
        );
        doReturn(Mono.just(new ErpNextListResponse<>(siItems)))
                .when(router).getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(),
                        any(org.springframework.core.ParameterizedTypeReference.class));

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, null, null, "most_ordered"))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(2);
                    assertThat(resp.data().get(0).productName()).isEqualTo("Beta Drug");
                    assertThat(resp.data().get(1).productName()).isEqualTo("Alpha Drug");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listProducts_alphabetical_sort_skips_order_frequency_and_returns_alphabetical_order() {
        InventoryItemResponse itemA = new InventoryItemResponse(
                "ITEM-A", "Zebra Drug", "", "Antibiotics",
                false, false, null, 0, 0, "Nos", List.of(), Map.of());
        InventoryItemResponse itemB = new InventoryItemResponse(
                "ITEM-B", "Aspirin", "", "Antibiotics",
                false, false, null, 0, 0, "Nos", List.of(), Map.of());
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemA, itemB)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of()));
        when(inventoryMapper.copyWithBatches(eq(itemA), anyList())).thenReturn(itemA);
        when(inventoryMapper.copyWithBatches(eq(itemB), anyList())).thenReturn(itemB);
        when(inventoryService.defaultWarehouse()).thenReturn(WAREHOUSE);
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE))).thenReturn(Mono.just(Map.of()));

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, null, null, "alphabetical"))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(2);
                    assertThat(resp.data().get(0).productName()).isEqualTo("Aspirin");
                    assertThat(resp.data().get(1).productName()).isEqualTo("Zebra Drug");
                })
                .verifyComplete();

        // ERPNext should NOT be contacted for Sales Invoice Items when sort=alphabetical
        verify(router, never()).getList(anyString(), eq("Sales Invoice Item"), anyMap(),
                any(org.springframework.core.ParameterizedTypeReference.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listProducts_order_frequency_error_falls_back_to_alphabetical() {
        InventoryItemResponse itemA = new InventoryItemResponse(
                "ITEM-A", "Zebra Drug", "", "Antibiotics",
                false, false, null, 0, 0, "Nos", List.of(), Map.of());
        InventoryItemResponse itemB = new InventoryItemResponse(
                "ITEM-B", "Aspirin", "", "Antibiotics",
                false, false, null, 0, 0, "Nos", List.of(), Map.of());
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemA, itemB)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of()));
        when(inventoryMapper.copyWithBatches(eq(itemA), anyList())).thenReturn(itemA);
        when(inventoryMapper.copyWithBatches(eq(itemB), anyList())).thenReturn(itemB);
        when(inventoryService.defaultWarehouse()).thenReturn(WAREHOUSE);
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE))).thenReturn(Mono.just(Map.of()));

        // Simulate ERPNext being unavailable
        doReturn(Mono.<ErpNextListResponse<?>>error(new RuntimeException("ERPNext unreachable")))
                .when(router).getList(eq(TENANT), eq("Sales Invoice Item"), anyMap(),
                        any(org.springframework.core.ParameterizedTypeReference.class));

        // Should complete successfully, falling back to alphabetical order
        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, null, null, "most_ordered"))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(2);
                    assertThat(resp.data().get(0).productName()).isEqualTo("Aspirin");
                    assertThat(resp.data().get(1).productName()).isEqualTo("Zebra Drug");
                })
                .verifyComplete();
    }

    // ---- createProduct ----------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void createProduct_success_creates_item_and_returns_detail() {
        InventoryApiSchemas.CreateProductRequest req = new InventoryApiSchemas.CreateProductRequest(
                "Amoxicillin 500mg", "Amoxicillin", null, "Antibiotics",
                "PPB-001", null, null, "500mg", "Capsules", null, null, null,
                Enums.UnitOfMeasure.capsules, 50.0, 500.0, null, null, null);

        // Capture the randomly-generated itemCode so we can return the right item from listItems
        java.util.concurrent.atomic.AtomicReference<String> capturedCode = new java.util.concurrent.atomic.AtomicReference<>();
        when(inventoryService.listItems(TENANT))
                .thenAnswer(inv -> {
                    String code = capturedCode.get();
                    if (code == null) return Mono.just(List.of());
                    return Mono.just(List.of(item(code)));
                });
        when(router.create(eq(TENANT), eq("Item"), anyMap(), eq(SINGLE_TYPE)))
                .thenAnswer(inv -> {
                    Map<String, Object> body = inv.getArgument(2);
                    String code = (String) body.get("item_code");
                    capturedCode.set(code);
                    return Mono.just(singleResponse(itemDoc(code)));
                });
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of()));
        when(inventoryMapper.copyWithBatches(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryService.defaultWarehouse()).thenReturn(WAREHOUSE);
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE))).thenReturn(Mono.just(Map.of()));

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
                "Another Drug", "Drug", null, "Antibiotics",
                "PPB-001", null, null, null, null, null, null, null,
                Enums.UnitOfMeasure.tablets, 10.0, 100.0, null, null, null);

        StepVerifier.create(service.createProduct(TENANT, req))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    void createProduct_throws_validation_error_when_product_name_missing() {
        InventoryApiSchemas.CreateProductRequest req = new InventoryApiSchemas.CreateProductRequest(
                "", "Generic", null, "Antibiotics",
                null, null, null, null, null, null, null, null,
                Enums.UnitOfMeasure.tablets, 10.0, 100.0, null, null, null);

        StepVerifier.create(Mono.defer(() -> service.createProduct(TENANT, req)))
                .expectError(ServiceValidationException.class)
                .verify();
    }

    @Test
    void createProduct_throws_validation_error_when_category_missing() {
        InventoryApiSchemas.CreateProductRequest req = new InventoryApiSchemas.CreateProductRequest(
                "Drug", "Generic", null, null,
                null, null, null, null, null, null, null, null,
                Enums.UnitOfMeasure.tablets, 10.0, 100.0, null, null, null);

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
                null, null, null, null, null, null);
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
    void getProduct_includes_available_quantity_distinct_from_total_stock() {
        stubListItemsAndBatches(ITEM_CODE, 100.0);
        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.getProduct(TENANT, productId))
                .assertNext(detail -> {
                    assertThat(detail.totalStock()).isEqualTo(100.0);
                    assertThat(detail.availableQuantity()).isEqualTo(90.0);
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
    void deleteProduct_soft_deletes_item_via_replace() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(router.getOne(eq(TENANT), eq("Item"), eq(ITEM_CODE), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(singleResponse(itemDoc(ITEM_CODE))));
        when(router.replace(eq(TENANT), eq("Item"), eq(ITEM_CODE), anyMap(), eq(SINGLE_TYPE)))
                .thenReturn(Mono.just(singleResponse(itemDoc(ITEM_CODE))));

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);

        StepVerifier.create(service.deleteProduct(TENANT, productId))
                .verifyComplete();

        verify(router).replace(eq(TENANT), eq("Item"), eq(ITEM_CODE),
                argThat(body -> body instanceof Map<?, ?> map
                        && Integer.valueOf(1).equals(map.get("disabled"))),
                eq(SINGLE_TYPE));
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
    void deleteBatch_submits_material_issue_then_deletes_batch() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        BatchResponse b1 = batch("erp-batch-1", ITEM_CODE, 50.0, "2026-06-30");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(b1)));
        stubMaterialIssueReversal(router, TENANT, "STE-REV-1", ITEM_CODE, 50.0);
        when(router.delete(eq(TENANT), eq("Batch"), eq("erp-batch-1"))).thenReturn(Mono.empty());

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        UUID batchId = StableEntityIds.batchId(TENANT, "erp-batch-1");

        StepVerifier.create(service.deleteBatch(TENANT, productId, batchId))
                .verifyComplete();

        verify(router).create(eq(TENANT), eq("Stock Entry"), anyMap(), eq(SINGLE_TYPE));
        verify(router).delete(TENANT, "Batch", "erp-batch-1");
    }

    @Test
    void deleteBatch_skips_stock_reversal_when_batch_quantity_is_zero() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        BatchResponse emptyBatch = batch("erp-batch-empty", ITEM_CODE, 0.0, "2026-06-30");
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of(emptyBatch)));
        when(router.delete(eq(TENANT), eq("Batch"), eq("erp-batch-empty"))).thenReturn(Mono.empty());

        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        UUID batchId = StableEntityIds.batchId(TENANT, "erp-batch-empty");

        StepVerifier.create(service.deleteBatch(TENANT, productId, batchId))
                .verifyComplete();

        verify(router, never()).create(eq(TENANT), eq("Stock Entry"), anyMap(), eq(SINGLE_TYPE));
        verify(router).delete(TENANT, "Batch", "erp-batch-empty");
    }

    // ---- addBatchJsonReturn -----------------------------------------------------

    @Test
    void addBatchJsonReturn_creates_batch_and_returns_it() {
        InventoryItemResponse itemResp = item(ITEM_CODE);
        // batchNumber must match req.batchNumber() so lastCreatedBatchForItem finds it
        BatchResponse createdBatch = new BatchResponse(
                "erp-b99", "BATCH-99", ITEM_CODE, 100.0, "available",
                "2027-01-01", null, "Main Warehouse", null, "2024-01-01", 10.0, 0.0, "Supplier A", null);
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(router.create(eq(TENANT), eq("Batch"), anyMap(), eq(SINGLE_TYPE)))
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
    void addBatchJsonReturn_rejects_zero_quantity() {
        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        InventoryApiSchemas.CreateBatchRequest req = new InventoryApiSchemas.CreateBatchRequest(
                "BATCH-99", "2027-01-01", "Supplier A", 0.0, null, 10.0, WAREHOUSE, null, null);

        StepVerifier.create(Mono.defer(() -> service.addBatchJsonReturn(TENANT, productId, req)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ServiceValidationException.class);
                    assertThat(err.getMessage()).contains("quantity (must be > 0)");
                })
                .verify();
    }

    @Test
    void addBatchJsonReturn_rejects_negative_quantity() {
        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        InventoryApiSchemas.CreateBatchRequest req = new InventoryApiSchemas.CreateBatchRequest(
                "BATCH-99", "2027-01-01", "Supplier A", -5.0, null, 10.0, WAREHOUSE, null, null);

        StepVerifier.create(Mono.defer(() -> service.addBatchJsonReturn(TENANT, productId, req)))
                .expectError(ServiceValidationException.class)
                .verify();
    }

    @Test
    void addBatchJsonReturn_throws_when_batch_number_missing() {
        UUID productId = StableEntityIds.itemId(TENANT, ITEM_CODE);
        InventoryApiSchemas.CreateBatchRequest req = new InventoryApiSchemas.CreateBatchRequest(
                null, "2027-01-01", "Supplier A", 100.0, null, 10.0, "Main Warehouse", null, null);

        StepVerifier.create(Mono.defer(() -> service.addBatchJsonReturn(TENANT, productId, req)))
                .expectError(ServiceValidationException.class)
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
                "2027-06-01", null, "Main Warehouse", null, "2024-01-01", 12.5, 0.0, null, null);
        when(router.create(eq(TENANT), eq("Batch"), anyMap(), eq(SINGLE_TYPE)))
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

    // ---- non-batch-tracked items ------------------------------------------------

    @Test
    void listProducts_nonBatchTracked_item_uses_availableQuantity_for_total_stock() {
        // Item has NO batches (non-batch-tracked), but Bin reports stock via availableQuantity.
        // Without the fix, total_stock would be 0 → status = out_of_stock.
        // With the fix, total_stock falls back to availableQuantity → status = available.
        InventoryItemResponse itemResp = new InventoryItemResponse(
                ITEM_CODE, "Paracetamol 500mg", "", "Analgesics",
                false, false, null, 20.0, 200.0, "Nos", List.of(), Map.of());
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of()));
        when(inventoryMapper.copyWithBatches(eq(itemResp), anyList())).thenReturn(itemResp);
        when(inventoryService.defaultWarehouse()).thenReturn(WAREHOUSE);
        // Bin reports 75 units available even though there are no Batch records
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE)))
                .thenReturn(Mono.just(Map.of(ITEM_CODE, 75.0)));

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, null, null, "most_ordered"))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(1);
                    InventoryApiSchemas.ProductSummary summary = resp.data().get(0);
                    assertThat(summary.totalStock()).isEqualTo(75.0);
                    assertThat(summary.status()).contains(Enums.ProductStatus.available);
                    assertThat(summary.status()).doesNotContain(Enums.ProductStatus.out_of_stock);
                })
                .verifyComplete();
    }

    @Test
    void listProducts_nonBatchTracked_item_with_zero_bin_stock_shows_out_of_stock() {
        InventoryItemResponse itemResp = new InventoryItemResponse(
                ITEM_CODE, "Paracetamol 500mg", "", "Analgesics",
                false, false, null, 20.0, 200.0, "Nos", List.of(), Map.of());
        when(inventoryService.listItems(TENANT)).thenReturn(Mono.just(List.of(itemResp)));
        when(inventoryService.listBatches(TENANT)).thenReturn(Mono.just(List.of()));
        when(inventoryMapper.copyWithBatches(eq(itemResp), anyList())).thenReturn(itemResp);
        when(inventoryService.defaultWarehouse()).thenReturn(WAREHOUSE);
        when(inventoryService.getStockLevels(eq(TENANT), anyList(), eq(WAREHOUSE)))
                .thenReturn(Mono.just(Map.of(ITEM_CODE, 0.0)));

        StepVerifier.create(service.listProducts(TENANT, 1, 10, null, null, null, null, "most_ordered"))
                .assertNext(resp -> {
                    assertThat(resp.data()).hasSize(1);
                    InventoryApiSchemas.ProductSummary summary = resp.data().get(0);
                    assertThat(summary.totalStock()).isEqualTo(0.0);
                    assertThat(summary.status()).contains(Enums.ProductStatus.out_of_stock);
                })
                .verifyComplete();
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
    void terminologyProduct_synthesises_record_for_arbitrary_id() {
        // The stub backfills a deterministic placeholder for any non-blank id so
        // that downstream consumers (e.g. /products/{id} called with a PPB code
        // returned by an external search) always get a usable response while
        // the real terminology integration is pending.
        StepVerifier.create(service.terminologyProduct("723"))
                .assertNext(p -> {
                    assertThat(p.terminologyId()).isEqualTo("723");
                    assertThat(p.brandName()).contains("723");
                })
                .verifyComplete();
    }
}
