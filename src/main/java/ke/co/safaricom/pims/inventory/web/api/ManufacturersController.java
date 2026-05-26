package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.web.service.ProductInventoryService;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(Constants.PUBLIC_API_ROOT + "/manufacturers")
@Tag(name = "Manufacturers", description = "InventoryApiSchemas.Manufacturer catalogue (seeded MVP)")
public class ManufacturersController {

    private final ProductInventoryService productInventoryService;
    private final TenantContextResolver tenants;

    public ManufacturersController(ProductInventoryService productInventoryService, TenantContextResolver tenants) {
        this.productInventoryService = productInventoryService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "List manufacturers")
    public Mono<InventoryApiSchemas.ManufacturersResponse> list(
            Authentication authentication,
            ServerWebExchange exchange,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String search) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(tenantId -> productInventoryService.manufacturers(search, limit))
                .map(InventoryApiSchemas.ManufacturersResponse::new);
    }
}
