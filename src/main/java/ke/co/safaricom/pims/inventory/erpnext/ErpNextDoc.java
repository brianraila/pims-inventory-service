package ke.co.safaricom.pims.inventory.erpnext;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Loose representation of common ERPNext DocType fields.
 * Services deserialise into this and mappers project to clean DTOs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErpNextDoc(

        // --- common ---
        String name,
        String owner,
        @JsonProperty("creation") String creation,
        @JsonProperty("modified") String modified,
        @JsonProperty("modified_by") String modifiedBy,
        @JsonProperty("docstatus") Integer docstatus,
        @JsonProperty("title") String taxTemplateTitle,
        @JsonProperty("is_default") Integer isDefault,
        @JsonProperty("disabled") Integer disabled,

        // --- Item ---
        @JsonProperty("item_name") String itemName,
        @JsonProperty("item_group") String itemGroup,
        @JsonProperty("stock_uom") String stockUom,
        @JsonProperty("description") String description,
        @JsonProperty("is_stock_item") Integer isStockItem,
        @JsonProperty("reorder_levels") java.util.List<java.util.Map<String, Object>> reorderLevels,

        // --- Item (PMIS custom columns) ---
        @JsonProperty("custom_pims_ppb_code") String customPimsPpbCode,
        @JsonProperty("custom_pims_ndc_code") String customPimsNdcCode,
        @JsonProperty("custom_pims_regulatory_status") String customPimsRegulatoryStatus,
        @JsonProperty("custom_pims_strength") String customPimsStrength,
        @JsonProperty("custom_pims_dosage_form") String customPimsDosageForm,
        @JsonProperty("custom_pims_terminology_source") String customPimsTerminologySource,
        @JsonProperty("custom_pims_terminology_id") String customPimsTerminologyId,
        @JsonProperty("custom_pims_additional_notes") String customPimsAdditionalNotes,
        @JsonProperty("custom_pims_manufacturer_uuid") String customPimsManufacturerUuid,
        @JsonProperty("custom_pims_supplier") String customPimsSupplier,
        @JsonProperty("custom_pims_unit_of_measure") String customPimsUnitOfMeasure,
        @JsonProperty("custom_pims_reorder_level") Double customPimsReorderLevel,
        @JsonProperty("custom_pims_maximum_stock") Double customPimsMaximumStock,
        @JsonProperty("custom_pims_controlled_substance") Integer customPimsControlledSubstance,
        @JsonProperty("custom_pims_controlled_schedule") String customPimsControlledSchedule,
        @JsonProperty("custom_pims_cold_chain_required") Integer customPimsColdChainRequired,
        @JsonProperty("custom_pims_photosensitive") Integer customPimsPhotosensitive,
        @JsonProperty("custom_pims_photosensitive_shelf_life_months") Integer customPimsPhotosensitiveShelfLifeMonths,
        @JsonProperty("custom_pims_generic_name") String customPimsGenericName,

        // --- Item Price ---
        @JsonProperty("price_list_rate") Double priceListRate,

        // --- Bin (current stock per warehouse) ---
        @JsonProperty("item_code") String itemCode,
        @JsonProperty("warehouse") String warehouse,
        @JsonProperty("actual_qty") Double actualQty,
        @JsonProperty("reserved_qty") Double reservedQty,

        // --- Batch ---
        @JsonProperty("item") String item,
        @JsonProperty("batch_id") String batchId,
        @JsonProperty("expiry_date") String expiryDate,
        @JsonProperty("manufacturing_date") String manufacturingDate,
        @JsonProperty("supplier") String supplier,
        @JsonProperty("batch_qty") Double batchQty,
        @JsonProperty("pims_unit_cost") Double customPimsUnitCost,
        @JsonProperty("pims_trade_cost") Double customPimsTradeCost,

        // --- Supplier ---
        @JsonProperty("supplier_name") String supplierName,
        @JsonProperty("supplier_group") String supplierGroup,
        @JsonProperty("supplier_type") String supplierType,
        @JsonProperty("tax_id") String taxId,
        @JsonProperty("supplier_details") String supplierDetails,
        @JsonProperty("country") String country,
        @JsonProperty("supplier_group_name") String supplierGroupName,
        @JsonProperty("parent_supplier_group") String parentSupplierGroup,

        // --- Warehouse ---
        @JsonProperty("warehouse_name") String warehouseName,

        // --- Item Group ---
        @JsonProperty("item_group_name") String itemGroupName,
        @JsonProperty("parent_item_group") String parentItemGroup,
        @JsonProperty("is_group") Integer isGroup,

        // --- Stock Entry ---
        @JsonProperty("stock_entry_type") String stockEntryType,
        @JsonProperty("purpose") String purpose,
        @JsonProperty("posting_date") String postingDate,
        @JsonProperty("remarks") String remarks,
        @JsonProperty("from_warehouse") String fromWarehouse,
        @JsonProperty("to_warehouse") String toWarehouse,
        @JsonProperty("items") java.util.List<java.util.Map<String, Object>> items,

        // --- Purchase Order / Sales Invoice shared ---
        @JsonProperty("status") String status,
        @JsonProperty("transaction_date") String transactionDate,
        @JsonProperty("schedule_date") String scheduleDate,
        @JsonProperty("grand_total") Double grandTotal,
        @JsonProperty("items_count") Integer itemsCount,
        @JsonProperty("total_qty") Double totalQty,
        @JsonProperty("per_received") Double perReceived,
        @JsonProperty("per_billed") Double perBilled,

        // --- Sales Invoice / Tax Template ---
        @JsonProperty("company") String company,
        @JsonProperty("taxes_and_charges") String taxesAndCharges,
        @JsonProperty("taxes") java.util.List<java.util.Map<String, Object>> taxes,

        // --- Sales Invoice ---
        @JsonProperty("customer") String customer,
        @JsonProperty("currency") String currency,
        @JsonProperty("net_total") Double netTotal,
        @JsonProperty("total_taxes_and_charges") Double totalTaxesAndCharges,

        // --- Payment (Sales Invoice + Payment Entry) ---
        @JsonProperty("is_pos") Integer isPos,
        @JsonProperty("paid_amount") Double paidAmount,
        @JsonProperty("outstanding_amount") Double outstandingAmount,
        @JsonProperty("mode_of_payment") String modeOfPayment,
        @JsonProperty("reference_no") String referenceNo,
        @JsonProperty("reference_date") String referenceDate,
        @JsonProperty("party") String party,

        // --- Sales Invoice (PMIS custom) ---
        @JsonProperty("custom_pims_prescription_id")
        @JsonAlias("pims_prescription_id")
        String customPimsPrescriptionId,
        @JsonProperty("custom_pims_sale_type")
        @JsonAlias("pims_sale_type")
        String customPimsSaleType,
        @JsonProperty("custom_pims_order_status")
        @JsonAlias("pims_order_status")
        String customPimsOrderStatus
) {}
