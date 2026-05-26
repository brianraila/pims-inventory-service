package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.web.service.ProductInventoryService;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping(Constants.API_PREFIX + "/products/{product_id}/adjustments")
@Tag(name = "Stock Adjustments", description = "Adjustments scoped to product")
public class InventoryAdjustmentController {

    private final ProductInventoryService productInventoryService;
    private final TenantContextResolver tenants;

    public InventoryAdjustmentController(ProductInventoryService productInventoryService, TenantContextResolver tenants) {
        this.productInventoryService = productInventoryService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "List adjustments for product")
    public Mono<InventoryApiSchemas.AdjustmentListResponse> list(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("product_id") UUID productId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> productInventoryService.listAdjustments(t, productId, page, limit));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit stock adjustment for batch")
    public Mono<InventoryApiSchemas.StockAdjustment> create(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("product_id") UUID productId,
            @Valid @RequestBody InventoryApiSchemas.StockAdjustmentRequest body) {
        UserBits bits = bits(authentication);
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> productInventoryService.adjustStock(t, productId, body, bits.email(), bits.name()));
    }

    private static UserBits bits(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String email = Optional.ofNullable(jwt.getToken().getClaimAsString("email")).orElse("");
            String name =
                    jwt.getToken().getClaimAsString("name") != null
                            ? jwt.getToken().getClaimAsString("name")
                            : Optional.ofNullable(jwt.getToken().getClaimAsString("preferred_username"))
                                    .orElse("unknown");
            if (!org.springframework.util.StringUtils.hasText(name)) {
                name = "unknown";
            }
            return new UserBits(email, name);
        }
        return new UserBits("", "anonymous");
    }

    private record UserBits(String email, String name) {}
}
