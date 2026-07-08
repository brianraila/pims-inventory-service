package ke.co.safaricom.pims.inventory.web.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Collections;
import java.util.List;

/** Canonical PMIS Inventory API DTO grouping (nested records satisfy one-public-class-per-file rule). */
public final class InventoryApiSchemas {

    private InventoryApiSchemas() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Pagination(int page, int limit, long total, long totalPages) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Manufacturer(java.util.UUID id, String name, String country, Boolean isActive) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManufacturersResponse(List<Manufacturer> data) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UserRef(java.util.UUID id, String name, String email) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpecialRequirements(
            Boolean controlledSubstance,
            String controlledSubstanceSchedule,
            Boolean coldChainRequired,
            Boolean photosensitive,
            Integer photosensitiveShelfLifeMonths) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StockAlert(String type, String message, String severity) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductListBatch(
            String batchNumber,
            double quantity,
            Enums.UnitOfMeasure unitOfMeasure,
            double unitValue,
            double stockValue,
            String manufactureDate,
            String expiryDate,
            String status) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductSummary(
            java.util.UUID id,
            String productName,
            String genericName,
            String category,
            double totalStock,
            double availableQuantity,
            Enums.UnitOfMeasure unitOfMeasure,
            int batchCount,
            List<Enums.ProductStatus> status,
            String imageUrl,
            String createdAt,
            String updatedAt,
            Double unitPrice,
            String currency,
            Double tradeCost,
            Double totalValue,
            Double sellingPrice,
            Double orderFrequency,
            Double itemsSold,
            double reorderLevel,
            List<ProductListBatch> batches) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Batch(
            java.util.UUID id,
            java.util.UUID productId,
            String batchNumber,
            Enums.BatchStatus status,
            double quantity,
            Enums.UnitOfMeasure unitOfMeasure,
            String dateOfPurchase,
            String expiryDate,
            String supplier,
            Double tradeCost,
            double unitCost,
            double totalCost,
            String currency,
            String storageLocation,
            String branch,
            String grnNumber,
            String manufacturer,
            String category,
            String createdAt,
            String updatedAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchListResponse(List<Batch> data, Pagination pagination) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchBulkUploadResult(
            int totalRows,
            int successCount,
            int errorCount,
            List<BatchCsvErrorRow> errors,
            List<Batch> createdBatches) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BatchCsvErrorRow(Integer row, String field, String message) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductDetail(
            java.util.UUID id,
            String productName,
            String genericName,
            String category,
            double totalStock,
            double availableQuantity,
            Enums.UnitOfMeasure unitOfMeasure,
            int batchCount,
            List<Enums.ProductStatus> status,
            String imageUrl,
            String createdAt,
            String updatedAt,
            Manufacturer manufacturer,
            String ppbCode,
            String ndcCode,
            Enums.RegulatoryStatus regulatoryStatus,
            String strength,
            String dosageForm,
            String additionalNotes,
            String terminologySource,
            String terminologyId,
            double reorderLevel,
            double maximumStock,
            SpecialRequirements specialRequirements,
            Double currentStock,
            Double totalValue,
            String currency,
            Double reorderQuantity,
            List<StockAlert> alerts,
            BatchListResponse batches,
            Double unitPrice,
            Double tradeCost,
            Double sellingPrice) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductListResponse(List<ProductSummary> data, Pagination pagination, ProductListSummary summary) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductListSummary(Long totalProducts, Long totalBatches) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateProductRequest(
            @NotBlank(message = "product_name is required") String productName,
            @NotBlank(message = "generic_name is required") String genericName,
            java.util.UUID manufacturerId,
            @NotBlank(message = "category is required") String category,
            String ppbCode,
            String ndcCode,
            Enums.RegulatoryStatus regulatoryStatus,
            String strength,
            String dosageForm,
            String additionalNotes,
            String terminologySource,
            String terminologyId,
            @NotNull(message = "unit_of_measure is required") Enums.UnitOfMeasure unitOfMeasure,
            @NotNull(message = "reorder_level is required") Double reorderLevel,
            @NotNull(message = "maximum_stock is required") Double maximumStock,
            Double sellingPrice,
            SpecialRequirements specialRequirements,
            List<CreateBatchRequest> initialBatches) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateProductRequest(
            String productName,
            String genericName,
            java.util.UUID manufacturerId,
            String category,
            String ppbCode,
            String ndcCode,
            Enums.RegulatoryStatus regulatoryStatus,
            String strength,
            String dosageForm,
            String additionalNotes,
            Enums.UnitOfMeasure unitOfMeasure,
            Double reorderLevel,
            Double maximumStock,
            Double sellingPrice,
            SpecialRequirements specialRequirements) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateBatchRequest(
            @NotBlank(message = "batch_number is required") String batchNumber,
            @NotBlank(message = "expiry_date is required") String expiryDate,
            String supplier,
            @NotNull(message = "quantity is required") @Positive(message = "quantity must be greater than 0") Double quantity,
            Double tradeCost,
            @NotNull(message = "unit_cost is required") @Positive(message = "unit_cost must be greater than 0") Double unitCost,
            @NotBlank(message = "storage_location is required") String storageLocation,
            String branch,
            String grnNumber) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StockAdjustmentRequest(
            @NotNull(message = "batch_id is required") java.util.UUID batchId,
            @NotNull(message = "adjustment_type is required") Enums.AdjustmentDirection adjustmentType,
            @Positive(message = "quantity must be greater than 0") double quantity,
            @NotNull(message = "reason is required") Enums.AdjustmentReason reason,
            String notes,
            String referenceNumber) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StockAdjustment(
            java.util.UUID id,
            java.util.UUID productId,
            java.util.UUID batchId,
            String batchNumber,
            Enums.AdjustmentDirection adjustmentType,
            double quantity,
            double quantityBefore,
            double quantityAfter,
            Enums.AdjustmentReason reason,
            String notes,
            String referenceNumber,
            UserRef performedBy,
            String createdAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdjustmentListResponse(List<StockAdjustment> data, Pagination pagination) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductDraft(java.util.UUID id, Enums.WizardStep wizardStep, CreateProductRequest data, String createdAt, String updatedAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductDraftRequest(Enums.WizardStep wizardStep, CreateProductRequest data) {

        public ProductDraftRequest {
            if (wizardStep == null) {
                throw new IllegalArgumentException("wizard_step required");
            }
            if (data == null) {
                data = emptyDraftData();
            }
        }

        private static CreateProductRequest emptyDraftData() {
            return new CreateProductRequest(
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, Collections.emptyList());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TerminologySearchResult(
            String terminologyId,
            Enums.TerminologyRecordSource source,
            String productName,
            String genericName,
            String manufacturerName,
            String ndcCode,
            String strength,
            String dosageForm) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TerminologySearchResponse(
            List<TerminologySearchResult> data,
            Integer total,
            List<Enums.TerminologyRecordSource> sourceQueried) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TerminologyProduct(
            String terminologyId,
            Enums.TerminologyRecordSource source,
            String brandName,
            String genericName,
            Manufacturer manufacturer,
            String category,
            String ppbCode,
            String ndcCode,
            Enums.RegulatoryStatus regulatoryStatus,
            String strength,
            String dosageForm) {}

    // ---- Metadata lookup responses ------------------------------------------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UomOption(String value, String label) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SupplierOption(String name, String supplierName) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WarehouseOption(String name, String warehouseName) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MetaListResponse<T>(List<T> data) {}

    // ---- Item Group (category) management ---------------------------------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Category(String id, String name, String parentCategory, Boolean isGroup) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateCategoryRequest(
            @NotBlank(message = "name is required") String name,
            String parentCategory,
            Boolean isGroup) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateCategoryRequest(
            @NotBlank(message = "name cannot be blank") String name, String parentCategory, Boolean isGroup) {}
}
