package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.api.dto.CreateSupplierRequest;
import ke.co.safaricom.pims.inventory.api.dto.SupplierPage;
import ke.co.safaricom.pims.inventory.api.dto.SupplierResponse;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.SupplierService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping(Constants.API_PREFIX + "/suppliers")
@Tag(name = "Suppliers", description = "Manage suppliers in ERPNext (tenant-aware)")
public class SupplierController {

    private final SupplierService supplierService;
    private final TenantContextResolver tenants;

    public SupplierController(SupplierService supplierService, TenantContextResolver tenants) {
        this.supplierService = supplierService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "List suppliers for the tenant (server-side name search + pagination)")
    public Mono<SupplierPage> list(Authentication auth, ServerWebExchange exchange,
                                   @RequestParam(required = false) String search,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> supplierService.listSuppliers(tenantId, search, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single supplier")
    public Mono<SupplierResponse> get(Authentication auth, ServerWebExchange exchange,
                                      @PathVariable String id) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> supplierService.getSupplier(tenantId, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a supplier in ERPNext")
    public Mono<SupplierResponse> create(Authentication auth, ServerWebExchange exchange,
                                         @Valid @RequestBody CreateSupplierRequest request) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> supplierService.createSupplier(tenantId, request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a supplier")
    public Mono<SupplierResponse> update(Authentication auth, ServerWebExchange exchange,
                                         @PathVariable String id,
                                         @Valid @RequestBody CreateSupplierRequest request) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> supplierService.updateSupplier(tenantId, id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a supplier")
    public Mono<ResponseEntity<Void>> delete(Authentication auth, ServerWebExchange exchange,
                                             @PathVariable String id) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> supplierService.deleteSupplier(tenantId, id))
                .thenReturn(ResponseEntity.noContent().build());
    }
}
