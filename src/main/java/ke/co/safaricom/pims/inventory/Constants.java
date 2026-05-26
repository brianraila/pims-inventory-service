package ke.co.safaricom.pims.inventory;

/** Base paths for routed HTTP controllers. Canonical OpenAPI assumes {@link #PUBLIC_API_ROOT} as server URL suffix. */
public final class Constants {

    /** Matches API Gateway conventions: authenticated routes under '/api/v1/**'. */
    public static final String PUBLIC_API_ROOT = "/api/v1";

    /** Inventory REST prefix: `/api/v1/inventory` (products, purchase orders). */
    public static final String API_PREFIX = PUBLIC_API_ROOT + "/inventory";

    /** Product inventory routes: `/api/v1/inventory/products`. */
    public static final String INVENTORY_PRODUCTS_ROOT = API_PREFIX + "/products";

    private Constants() {}
}
