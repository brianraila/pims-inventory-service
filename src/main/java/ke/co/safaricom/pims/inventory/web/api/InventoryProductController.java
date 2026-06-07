package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.service.ProductInventoryService;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(Constants.INVENTORY_PRODUCTS_ROOT)
@Tag(name = "Products", description = "Product catalogue per PMIS Inventory API contract")
public class InventoryProductController {

    private final ProductInventoryService productInventoryService;
    private final TenantContextResolver tenants;

    public InventoryProductController(ProductInventoryService productInventoryService, TenantContextResolver tenants) {
        this.productInventoryService = productInventoryService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "List all products (paginated)",
            description = "Products are sorted by most-ordered (default) or alphabetically. " +
                    "Use ?sort=alphabetical to disable order-frequency ranking. " +
                    "When sort=most_ordered, optional sort_days limits history to the last N days " +
                    "and rank_by chooses qty (total units sold) or orders (distinct invoice count).")
    public Mono<InventoryApiSchemas.ProductListResponse> list(
            Authentication authentication,
            ServerWebExchange exchange,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Enums.ProductStatus status,
            @RequestParam(value = "manufacturer_id", required = false) UUID manufacturerId,
            @RequestParam(value = "sort", defaultValue = "most_ordered") String sort,
            @RequestParam(value = "sort_days", required = false) Integer sortDays,
            @RequestParam(value = "rank_by", defaultValue = "qty") String rankBy) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> productInventoryService.listProducts(
                        t, page, limit, search, category, status, manufacturerId, sort, sortDays, rankBy));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create product (wizard Step 4 submit)")
    public Mono<InventoryApiSchemas.ProductDetail> create(
            Authentication authentication,
            ServerWebExchange exchange,
            @Valid @RequestBody InventoryApiSchemas.CreateProductRequest request) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> productInventoryService.createProduct(t, request));
    }

    @PostMapping(value = "/drafts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save product wizard draft")
    public Mono<InventoryApiSchemas.ProductDraft> saveDraft(
            Authentication authentication, ServerWebExchange exchange, @RequestBody InventoryApiSchemas.ProductDraftRequest body) {
        return tenants.resolveTenantId(authentication, exchange).flatMap(t -> productInventoryService.saveDraft(t, body));
    }

    @GetMapping("/drafts/{draft_id}")
    @Operation(summary = "Get product wizard draft")
    public Mono<InventoryApiSchemas.ProductDraft> getDraft(
            Authentication authentication, ServerWebExchange exchange, @PathVariable("draft_id") UUID draftId) {
        return tenants.resolveTenantId(authentication, exchange).flatMap(t -> productInventoryService.getDraft(t, draftId));
    }

    @PatchMapping(value = "/drafts/{draft_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Patch product wizard draft")
    public Mono<InventoryApiSchemas.ProductDraft> patchDraft(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("draft_id") UUID draftId,
            @RequestBody InventoryApiSchemas.ProductDraftRequest body) {
        return tenants.resolveTenantId(authentication, exchange).flatMap(t -> productInventoryService.patchDraft(t, draftId, body));
    }

    @GetMapping("/{product_id}")
    @Operation(summary = "Get product detail")
    public Mono<InventoryApiSchemas.ProductDetail> detail(
            Authentication authentication, ServerWebExchange exchange, @PathVariable("product_id") UUID productId) {
        return tenants.resolveTenantId(authentication, exchange).flatMap(t -> productInventoryService.getProduct(t, productId));
    }

    @PatchMapping(value = "/{product_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update product")
    public Mono<InventoryApiSchemas.ProductDetail> patch(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("product_id") UUID productId,
            @RequestBody InventoryApiSchemas.UpdateProductRequest body) {
        return tenants.resolveTenantId(authentication, exchange).flatMap(t -> productInventoryService.updateProduct(t, productId, body));
    }

    @DeleteMapping("/{product_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete/archive product")
    public Mono<Void> delete(
            Authentication authentication, ServerWebExchange exchange, @PathVariable("product_id") UUID productId) {
        return tenants.resolveTenantId(authentication, exchange).flatMap(t -> productInventoryService.deleteProduct(t, productId));
    }
}
