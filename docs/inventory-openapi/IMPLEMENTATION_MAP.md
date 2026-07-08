# PMIS Inventory API — implementation map

Operational mapping between the **`inventory-api-v1.yaml`** contract and `ms-pims-inventory-service`.

| Area | Implemented in code | Persistence / backends |
|------|---------------------|------------------------|
| **Products** CRUD | `web.api.InventoryProductController` + `ProductInventoryService` | ERPNext **`Item`**; pharmacy-oriented attributes on **`custom_pims_*`** columns (Frappe **`pims`** patch **`create_pims_item_custom_fields`**) plus legacy **`description`** fragment (`<<<PIMS_ITEM_EXTRAS>>>`) via `ItemExtrasCodec`. Stable UUIDs derive from **`StableEntityIds`** keyed by `(tenant_id, ERP item code)`. Operator mapping: [**PRODUCT_ID_AND_ERP_MAPPING.md**](PRODUCT_ID_AND_ERP_MAPPING.md). |
| **Product drafts** | `ProductDraftMemoryStore` | **In-memory** per JVM (non-durable MVP). Swap for Postgres/Redis keyed by `tenant_id` + `draft_id`. |
| **Batches** | `web.api.InventoryBatchController` + ERPNext **`Batch`** doctype POST | Tenant inventory batch reads still merge **`BatchResponse`** projections; CSV ingestion is minimal column contract (see YAML). |
| **Adjustments** | `web.api.InventoryAdjustmentController` + reuse `InventoryService.createAdjustment` | ERPNext **`Stock Entry`** Material Receipt/Issue flows; UUID batch linkage aligns with deterministic batch IDs. |
| **Terminology** | `web.util.TerminologyStub` + `TerminologyController` | **Stub catalogue** returning sample hits. Replace module with RxNorm FHIR/National PPB integrations + caching. |
| **Manufacturers** | `ManufacturersCatalog` seeded list + `ManufacturersController` | **Static seed** (`ManufacturersCatalog`). Replace with master data store or ERP **Supplier** linkage. |
| **Suppliers** | `web.api.SupplierController` + `SupplierService` + `SupplierMapper` | ERPNext **`Supplier`** doctype. Contact and PPB/licensing metadata are composed into **`supplier_details`**; registration number maps to **`tax_id`**. |
| **Supplier groups** | `web.api.SupplierGroupController` + `SupplierService` | ERPNext **`Supplier Group`** doctype (list/create/update/delete). |
| **Supplier meta** | `web.api.InventoryMetaController` (`GET /meta/suppliers`) + `InventoryService.listSuppliers` | Active ERPNext suppliers only (picker for batches, POs, product forms). |
| **Purchase orders** | `api.PurchaseOrderController` + `PurchaseOrderService` | ERPNext **`Purchase Order`** (list/create). |

**Removed (breaking):** Legacy REST **`/api/v1/inventory/items`**, **`/batches`**, **`/adjustments`**, **`/transfers`** (`InventoryController`). Use **`/inventory/products`** and nested batch/adjustment routes per OpenAPI.

## Gateway expectations

Consumers calling through **`ms-pims-api-gateway`** should use bearer JWTs validated by Keycloak.

Downstream **`ms-pims-inventory-service`** must receive **`X-Tenant-Id`** (JWT `tenant_id` / `tenantId` claim mirrored by gateway) whenever the JWT omits tenant context. Product UUID ↔ ERP **`item_code`** notes: [**PRODUCT_ID_AND_ERP_MAPPING.md**](PRODUCT_ID_AND_ERP_MAPPING.md).

## OpenAPI publication policy

- **`src/main/resources/static/openapi/inventory-api-v1.yaml`** is the **canonical** contract for external consumers.
- **`/v3/api-docs`** (SpringDoc) reflects runtime annotations and may differ slightly; use it for exploratory tooling only unless explicitly aligned with the YAML in CI.
