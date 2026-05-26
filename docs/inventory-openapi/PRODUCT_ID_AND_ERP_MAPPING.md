# Product ID ↔ ERP Item mapping

This note is for operators and API consumers integrating with **`ms-pims-inventory-service`**.

## Stable product UUID

- HTTP responses expose **`product_id`** values as UUIDs (OpenAPI: **`product_id`** path/query parameters).
- Those UUIDs are **deterministic per tenant** and ERP **`item_code`**, computed in **`StableEntityIds.itemId(tenant_id, erp_item_code)`** (same inputs → same UUID).
- ERPNext still stores the authoritative key as **`Item.item_code`** / document **`name`** (typically identical).

## Tenant routing

- Requests via **`ms-pims-api-gateway`** must carry **`X-Tenant-Id`** when the JWT does not already encode tenant context. See [**IMPLEMENTATION_MAP.md**](IMPLEMENTATION_MAP.md).

## PMIS columns on Item

- Pharmacy-oriented attributes are mirrored on **`Item`** custom fields ( **`custom_pims_*`** ), installed by Frappe app **`pims`** patch **`pims.patches.v1_0.create_pims_item_custom_fields`**.
- The service **also** keeps the legacy **`description`** fragment (`<<<PIMS_ITEM_EXTRAS>>>`) for backward compatibility until data is fully migrated.

## Applying ERP schema updates

On each ERPNext site, run **`bench migrate`** after upgrading the **`pims`** app so Item custom fields exist before relying on column-level reporting.
