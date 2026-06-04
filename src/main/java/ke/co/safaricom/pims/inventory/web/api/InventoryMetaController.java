package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.InventoryService;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping(Constants.API_PREFIX + "/meta")
@Tag(name = "Metadata", description = "Lookup values for UOMs, suppliers, and warehouses")
public class InventoryMetaController {

    private final InventoryService inventoryService;
    private final TenantContextResolver tenants;

    public InventoryMetaController(InventoryService inventoryService, TenantContextResolver tenants) {
        this.inventoryService = inventoryService;
        this.tenants = tenants;
    }

    @GetMapping("/uom")
    @Operation(summary = "List available units of measure")
    public Mono<InventoryApiSchemas.MetaListResponse<InventoryApiSchemas.UomOption>> listUom() {
        List<InventoryApiSchemas.UomOption> options = Arrays.stream(Enums.UnitOfMeasure.values())
                .map(u -> new InventoryApiSchemas.UomOption(u.jsonName(), capitalize(u.jsonName())))
                .toList();
        return Mono.just(new InventoryApiSchemas.MetaListResponse<>(options));
    }

    @GetMapping("/suppliers")
    @Operation(summary = "List suppliers from ERPNext")
    public Mono<InventoryApiSchemas.MetaListResponse<InventoryApiSchemas.SupplierOption>> listSuppliers(
            Authentication authentication,
            ServerWebExchange exchange) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(inventoryService::listSuppliers)
                .map(InventoryApiSchemas.MetaListResponse::new);
    }

    @GetMapping("/warehouses")
    @Operation(summary = "List warehouses from ERPNext")
    public Mono<InventoryApiSchemas.MetaListResponse<InventoryApiSchemas.WarehouseOption>> listWarehouses(
            Authentication authentication,
            ServerWebExchange exchange) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(inventoryService::listWarehouses)
                .map(InventoryApiSchemas.MetaListResponse::new);
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
