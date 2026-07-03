package ke.co.safaricom.pims.inventory.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.api.dto.CreatePurchaseOrderRequest;
import ke.co.safaricom.pims.inventory.api.dto.PurchaseOrderResponse;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.PurchaseOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping(Constants.API_PREFIX + "/purchase-orders")
@Tag(name = "Purchase Orders", description = "Create and query purchase orders in ERPNext")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final TenantContextResolver tenantContextResolver;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService, TenantContextResolver tenantContextResolver) {
        this.purchaseOrderService = purchaseOrderService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping
    @Operation(summary = "List purchase orders for the tenant (optional supplier filter)")
    public Mono<List<PurchaseOrderResponse>> listPurchaseOrders(
            Authentication auth, ServerWebExchange exchange,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String supplier) {
        return tenantContextResolver.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> purchaseOrderService.listPurchaseOrders(tenantId, supplier));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a purchase order in ERPNext")
    public Mono<PurchaseOrderResponse> createPurchaseOrder(
            @Valid @RequestBody CreatePurchaseOrderRequest request,
            Authentication auth,
            ServerWebExchange exchange) {
        return tenantContextResolver.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> purchaseOrderService.createPurchaseOrder(tenantId, request));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    @Operation(summary = "Get a purchase order with its line items")
    public Mono<ke.co.safaricom.pims.inventory.api.dto.PurchaseOrderDetail> getPurchaseOrder(
            @org.springframework.web.bind.annotation.PathVariable String id,
            Authentication auth, ServerWebExchange exchange) {
        return tenantContextResolver.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> purchaseOrderService.getPurchaseOrder(tenantId, id));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    @Operation(summary = "Update a draft purchase order (rejected once submitted)")
    public Mono<ke.co.safaricom.pims.inventory.api.dto.PurchaseOrderDetail> updatePurchaseOrder(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @Valid @RequestBody ke.co.safaricom.pims.inventory.api.dto.UpdatePurchaseOrderRequest request,
            Authentication auth, ServerWebExchange exchange) {
        return tenantContextResolver.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> purchaseOrderService.updatePurchaseOrder(tenantId, id, request));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit a draft purchase order (finalise; no further edits)")
    public Mono<ke.co.safaricom.pims.inventory.api.dto.PurchaseOrderDetail> submitPurchaseOrder(
            @org.springframework.web.bind.annotation.PathVariable String id,
            Authentication auth, ServerWebExchange exchange) {
        return tenantContextResolver.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> purchaseOrderService.submitPurchaseOrder(tenantId, id));
    }
}
