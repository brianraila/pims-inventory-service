package ke.co.safaricom.pims.inventory.web.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

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
    public record ProductSummary(
            java.util.UUID id,
            String productName,
            String genericName,
            Enums.ProductCategory category,
            double totalStock,
            Enums.UnitOfMeasure unitOfMeasure,
            int batchCount,
            List<Enums.ProductStatus> status,
            String imageUrl,
            String createdAt,
            String updatedAt) {}

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
            Enums.ProductCategory category,
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
            Enums.ProductCategory category,
            double totalStock,
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
            BatchListResponse batches) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductListResponse(List<ProductSummary> data, Pagination pagination, ProductListSummary summary) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductListSummary(Long totalProducts, Long totalBatches) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateProductRequest(
            String productName,
            String genericName,
            java.util.UUID manufacturerId,
            Enums.ProductCategory category,
            String ppbCode,
            String ndcCode,
            Enums.RegulatoryStatus regulatoryStatus,
            String strength,
            String dosageForm,
            String additionalNotes,
            String terminologySource,
            String terminologyId,
            Enums.UnitOfMeasure unitOfMeasure,
            Double reorderLevel,
            Double maximumStock,
            SpecialRequirements specialRequirements,
            List<CreateBatchRequest> initialBatches) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateProductRequest(
            String productName,
            String genericName,
            java.util.UUID manufacturerId,
            Enums.ProductCategory category,
            String ppbCode,
            String ndcCode,
            Enums.RegulatoryStatus regulatoryStatus,
            String strength,
            String dosageForm,
            String additionalNotes,
            Enums.UnitOfMeasure unitOfMeasure,
            Double reorderLevel,
            Double maximumStock,
            SpecialRequirements specialRequirements) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateBatchRequest(
            String batchNumber,
            String expiryDate,
            String supplier,
            Double quantity,
            Double tradeCost,
            Double unitCost,
            String storageLocation,
            String branch,
            String grnNumber) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StockAdjustmentRequest(
            java.util.UUID batchId,
            Enums.AdjustmentDirection adjustmentType,
            double quantity,
            Enums.AdjustmentReason reason,
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
                    null, null, null, null, Collections.emptyList());
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
            Enums.ProductCategory category,
            String ppbCode,
            String ndcCode,
            Enums.RegulatoryStatus regulatoryStatus,
            String strength,
            String dosageForm) {}
}
