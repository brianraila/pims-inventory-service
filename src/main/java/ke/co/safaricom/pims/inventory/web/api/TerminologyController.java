package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.service.ProductInventoryService;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(Constants.API_PREFIX + "/terminology")
@Tag(name = "Terminology Service", description = "Stubbed RxNorm/PPB search (replace with live integration)")
public class TerminologyController {

    private final ProductInventoryService productInventoryService;
    private final TenantContextResolver tenants;

    public TerminologyController(ProductInventoryService productInventoryService, TenantContextResolver tenants) {
        this.productInventoryService = productInventoryService;
        this.tenants = tenants;
    }

    @GetMapping("/search")
    @Operation(summary = "Search terminology")
    public Mono<InventoryApiSchemas.TerminologySearchResponse> search(
            Authentication authentication,
            ServerWebExchange exchange,
            @RequestParam("q") String q,
            @RequestParam(value = "manufacturer_id", required = false) UUID manufacturerId,
            @RequestParam(required = false, defaultValue = "all") Enums.TerminologySource source,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        if (!StringUtils.hasText(q) || q.trim().length() < 2) {
            return Mono.error(new IllegalArgumentException("Query must be at least 2 characters"));
        }
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t ->
                        productInventoryService.searchTerminology(q, manufacturerId, source, limit));
    }

    @GetMapping("/products/{terminology_id}")
    @Operation(summary = "Get terminology product record")
    public Mono<InventoryApiSchemas.TerminologyProduct> product(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("terminology_id") String terminologyId) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> productInventoryService.terminologyProduct(terminologyId));
    }
}
