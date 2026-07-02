package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.api.dto.CreateSupplierGroupRequest;
import ke.co.safaricom.pims.inventory.api.dto.SupplierGroupResponse;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.SupplierService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
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

import java.util.List;

@RestController
@RequestMapping(Constants.API_PREFIX + "/supplier-groups")
@Tag(name = "Supplier Groups", description = "Manage supplier groups in ERPNext (tenant-aware)")
public class SupplierGroupController {

    private final SupplierService supplierService;
    private final TenantContextResolver tenants;

    public SupplierGroupController(SupplierService supplierService, TenantContextResolver tenants) {
        this.supplierService = supplierService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "List supplier groups for the tenant")
    public Mono<List<SupplierGroupResponse>> list(Authentication auth, ServerWebExchange exchange) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(supplierService::listSupplierGroups);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a supplier group in ERPNext")
    public Mono<SupplierGroupResponse> create(Authentication auth, ServerWebExchange exchange,
                                              @Valid @RequestBody CreateSupplierGroupRequest request) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> supplierService.createSupplierGroup(tenantId, request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a supplier group")
    public Mono<SupplierGroupResponse> update(Authentication auth, ServerWebExchange exchange,
                                              @PathVariable String id,
                                              @Valid @RequestBody CreateSupplierGroupRequest request) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> supplierService.updateSupplierGroup(tenantId, id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a supplier group")
    public Mono<ResponseEntity<Void>> delete(Authentication auth, ServerWebExchange exchange,
                                             @PathVariable String id) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(tenantId -> supplierService.deleteSupplierGroup(tenantId, id))
                .thenReturn(ResponseEntity.noContent().build());
    }
}
