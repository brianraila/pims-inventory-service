package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.service.ProductInventoryService;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(Constants.API_PREFIX + "/products/{product_id}/batches")
@Tag(name = "Batches", description = "Batches under a product")
public class InventoryBatchController {

    private final ProductInventoryService productInventoryService;
    private final TenantContextResolver tenants;

    public InventoryBatchController(ProductInventoryService productInventoryService, TenantContextResolver tenants) {
        this.productInventoryService = productInventoryService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "List batches")
    public Mono<InventoryApiSchemas.BatchListResponse> list(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("product_id") UUID productId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) Enums.BatchStatus status,
            @RequestParam(defaultValue = "fefo") String sort) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> productInventoryService.listBatches(t, productId, page, limit, status, sort));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Add batch via JSON")
    public Mono<ResponseEntity<Object>> createJson(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("product_id") UUID productId,
            @Valid @RequestBody InventoryApiSchemas.CreateBatchRequest body) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> productInventoryService.addBatchJsonReturn(t, productId, body).map(b -> ResponseEntity.status(HttpStatus.CREATED).body((Object) b)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bulk batch upload (CSV)")
    public Mono<ResponseEntity<Object>> createCsv(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("product_id") UUID productId,
            @org.springframework.web.bind.annotation.RequestPart("file") Mono<FilePart> file) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> productInventoryService.createBatchFlexible(t, productId, null, file)
                        .map(obj -> {
                            if (obj instanceof InventoryApiSchemas.BatchBulkUploadResult b) {
                                return ResponseEntity.status(HttpStatus.CREATED).body((Object) b);
                            }
                            return ResponseEntity.status(HttpStatus.CREATED).body(obj);
                        }));
    }

    @GetMapping("/{batch_id}")
    @Operation(summary = "Get batch")
    public Mono<InventoryApiSchemas.Batch> get(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("product_id") UUID productId,
            @PathVariable("batch_id") UUID batchId) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> productInventoryService.getBatch(t, productId, batchId));
    }

    @DeleteMapping("/{batch_id}")
    @Operation(summary = "Remove batch")
    public Mono<ResponseEntity<Void>> delete(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("product_id") UUID productId,
            @PathVariable("batch_id") UUID batchId) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> productInventoryService.deleteBatch(t, productId, batchId).thenReturn(ResponseEntity.noContent().build()));
    }
}
