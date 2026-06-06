package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.CategoryService;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(Constants.API_PREFIX + "/categories")
@Tag(name = "Categories", description = "ERPNext Item Group (product category) management")
public class InventoryCategoryController {

    private final CategoryService categoryService;
    private final TenantContextResolver tenants;

    public InventoryCategoryController(CategoryService categoryService, TenantContextResolver tenants) {
        this.categoryService = categoryService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "List all product categories (ERPNext Item Groups)")
    public Mono<InventoryApiSchemas.MetaListResponse<InventoryApiSchemas.Category>> list(
            Authentication authentication,
            ServerWebExchange exchange) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(categoryService::listCategories)
                .map(InventoryApiSchemas.MetaListResponse::new);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single product category")
    public Mono<InventoryApiSchemas.Category> get(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("id") String id) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> categoryService.getCategory(t, id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a product category")
    public Mono<InventoryApiSchemas.Category> create(
            Authentication authentication,
            ServerWebExchange exchange,
            @Valid @RequestBody InventoryApiSchemas.CreateCategoryRequest body) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> categoryService.createCategory(t, body));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a product category")
    public Mono<InventoryApiSchemas.Category> update(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("id") String id,
            @Valid @RequestBody InventoryApiSchemas.UpdateCategoryRequest body) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> categoryService.updateCategory(t, id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a product category")
    public Mono<Void> delete(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("id") String id) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> categoryService.deleteCategory(t, id));
    }
}
