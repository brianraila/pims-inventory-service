# Inventory OpenAPI changelog

All notable revisions to the canonical **`inventory-api-v1.yaml`** artifact.

## 1.1.0 — unreleased

- Frappe **`pims`** app: patch **`create_pims_item_custom_fields`** adds **`Item.custom_pims_*`** columns; Java maps create/update/read alongside the legacy description fragment.
- **Breaking:** Removed legacy REST endpoints **`GET /inventory/items`**, **`GET /inventory/items/{id}`**, **`GET /inventory/batches`**, **`GET|POST /inventory/adjustments`**, **`GET|POST /inventory/transfers`**. Use **`/inventory/products`**, **`/inventory/products/{product_id}/batches`**, **`/inventory/products/{product_id}/adjustments`** per this specification.
- Renamed implementation packages **`inventory.portal.*`** → **`inventory.web.*`**; types **`PortalSchemas`** → **`InventoryApiSchemas`**, **`PortalInventoryService`** → **`ProductInventoryService`**, and HTTP controllers accordingly (no URL path changes).
- Documented **`GET|POST /inventory/purchase-orders`** in this OpenAPI artifact (purchase order JSON uses **camelCase** fields to match the Java DTOs).

## 1.0.0 — 2026-05-18

- Initial published OpenAPI 3.0.3 describing the Inventory HTTP API surface (`/inventory/products`, terminology, manufacturers).
- Paths are anchored under **`servers[].url`** = `/api/v1` (consistent with **`ms-pims-api-gateway`** route prefix); resource paths retain `/inventory/...`, `/terminology/...`, `/manufacturers`.
- Resolved schema polish from the upstream draft:
  - **`ProductDraftRequest.data`** modeled as **`DraftData`** (`type: object` with optional mirrored fields instead of brittle `allOf` on required fields).
  - **`Batch` POST** documents JSON (`Batch`) vs multipart CSV (**`BatchBulkUploadResult`**) without ambiguous `discriminator`.
