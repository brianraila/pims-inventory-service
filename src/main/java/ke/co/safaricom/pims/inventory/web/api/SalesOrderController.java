package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.SalesOrderService;
import ke.co.safaricom.pims.inventory.web.model.SalesOrderSchemas;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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

@RestController
@RequestMapping(Constants.API_PREFIX + "/orders")
@Tag(name = "Orders", description = "POS order / sales invoice lifecycle")
public class SalesOrderController {

    private final SalesOrderService salesOrderService;
    private final TenantContextResolver tenants;

    public SalesOrderController(SalesOrderService salesOrderService, TenantContextResolver tenants) {
        this.salesOrderService = salesOrderService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "List orders for the tenant")
    public Mono<SalesOrderSchemas.OrderListResponse> list(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> salesOrderService.listOrders(t, page, limit, status, from, to));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a draft sales order")
    public Mono<SalesOrderSchemas.OrderResponse> create(
            Authentication auth,
            ServerWebExchange exchange,
            @Valid @RequestBody SalesOrderSchemas.CreateOrderRequest body) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> salesOrderService.createDraft(t, body));
    }

    @GetMapping("/{order_id}")
    @Operation(summary = "Get a single order")
    public Mono<SalesOrderSchemas.OrderResponse> get(
            Authentication auth,
            ServerWebExchange exchange,
            @PathVariable("order_id") String orderId) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> salesOrderService.getOrder(t, orderId));
    }

    @PatchMapping("/{order_id}/items")
    @Operation(summary = "Replace the item list on a draft order")
    public Mono<SalesOrderSchemas.OrderResponse> updateItems(
            Authentication auth,
            ServerWebExchange exchange,
            @PathVariable("order_id") String orderId,
            @Valid @RequestBody SalesOrderSchemas.UpdateOrderItemsRequest body) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> salesOrderService.updateItems(t, orderId, body));
    }

    @PostMapping("/{order_id}/submit")
    @Operation(summary = "Submit a draft order — deducts stock and finalises the invoice")
    public Mono<SalesOrderSchemas.OrderResponse> submit(
            Authentication auth,
            ServerWebExchange exchange,
            @PathVariable("order_id") String orderId,
            @RequestBody(required = false) SalesOrderSchemas.SubmitOrderRequest body) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> salesOrderService.submitOrder(t, orderId, body));
    }
}
