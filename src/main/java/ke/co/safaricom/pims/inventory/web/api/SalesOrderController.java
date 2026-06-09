package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.OrderPaymentService;
import ke.co.safaricom.pims.inventory.service.PaymentDetails;
import ke.co.safaricom.pims.inventory.service.ReceiptService;
import ke.co.safaricom.pims.inventory.service.SalesOrderService;
import ke.co.safaricom.pims.inventory.web.model.SalesOrderSchemas;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
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
    private final OrderPaymentService orderPaymentService;
    private final ReceiptService receiptService;
    private final TenantContextResolver tenants;

    public SalesOrderController(SalesOrderService salesOrderService,
                                OrderPaymentService orderPaymentService,
                                ReceiptService receiptService,
                                TenantContextResolver tenants) {
        this.salesOrderService = salesOrderService;
        this.orderPaymentService = orderPaymentService;
        this.receiptService = receiptService;
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

    @PostMapping("/{order_id}/pay")
    @Operation(summary = "Record payment for a submitted order as a Payment Entry (call after /submit); returns the change due")
    public Mono<SalesOrderSchemas.PaymentResponse> pay(
            Authentication auth,
            ServerWebExchange exchange,
            @PathVariable("order_id") String orderId,
            @Valid @RequestBody SalesOrderSchemas.PayOrderRequest body) {
        PaymentDetails details = new PaymentDetails(
                body.paymentMethod(), body.amountTendered(), body.transactionRef(), null, null, body.notes());
        SalesOrderSchemas.SubmitOrderRequest submitReq = new SalesOrderSchemas.SubmitOrderRequest(
                body.paymentMethod(), body.amountTendered(), null, body.notes());
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> salesOrderService.ensureSubmitted(t, orderId, submitReq)
                        .then(Mono.defer(() -> orderPaymentService.recordPayment(t, orderId, details))));
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

    @GetMapping(value = "/{order_id}/receipt", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Download a printable PDF receipt for a submitted order")
    public Mono<ResponseEntity<byte[]>> receipt(
            Authentication auth,
            ServerWebExchange exchange,
            @PathVariable("order_id") String orderId) {
        String dispensingPharmacist = dispensingPharmacistName(auth);
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> receiptService.generateReceipt(t, orderId, dispensingPharmacist))
                .map(pdf -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + orderId + "-receipt.pdf\"")
                        .body(pdf));
    }

    private static String dispensingPharmacistName(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String name = jwt.getToken().getClaimAsString("name");
            if (!StringUtils.hasText(name)) {
                name = jwt.getToken().getClaimAsString("preferred_username");
            }
            return StringUtils.hasText(name) ? name : "unknown";
        }
        return "unknown";
    }
}
