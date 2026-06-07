package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.TaxConfigService;
import ke.co.safaricom.pims.inventory.web.model.TaxSchemas;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
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

@RestController
@RequestMapping(Constants.API_PREFIX + "/taxes")
@Tag(name = "Tax Templates", description = "Sales Taxes and Charges Template management")
public class TaxController {

    private final TaxConfigService taxConfigService;
    private final TenantContextResolver tenants;

    public TaxController(TaxConfigService taxConfigService, TenantContextResolver tenants) {
        this.taxConfigService = taxConfigService;
        this.tenants = tenants;
    }

    @GetMapping("/templates")
    @Operation(summary = "List all Sales Taxes and Charges Templates for this tenant")
    public Mono<TaxSchemas.TaxTemplateListResponse> list(
            Authentication authentication,
            ServerWebExchange exchange) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(taxConfigService::listTemplates)
                .map(TaxSchemas.TaxTemplateListResponse::new);
    }

    @GetMapping("/templates/{name}")
    @Operation(summary = "Get a single Sales Taxes and Charges Template")
    public Mono<TaxSchemas.TaxTemplateResponse> get(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("name") String name) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> taxConfigService.getTemplate(t, name));
    }

    @PostMapping(value = "/templates", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new Sales Taxes and Charges Template")
    public Mono<TaxSchemas.TaxTemplateResponse> create(
            Authentication authentication,
            ServerWebExchange exchange,
            @Valid @RequestBody TaxSchemas.CreateTaxTemplateRequest body) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> taxConfigService.createTemplate(t, body));
    }

    @PutMapping(value = "/templates/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a Sales Taxes and Charges Template")
    public Mono<TaxSchemas.TaxTemplateResponse> update(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("name") String name,
            @Valid @RequestBody TaxSchemas.UpdateTaxTemplateRequest body) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> taxConfigService.updateTemplate(t, name, body));
    }

    @DeleteMapping("/templates/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a Sales Taxes and Charges Template")
    public Mono<Void> delete(
            Authentication authentication,
            ServerWebExchange exchange,
            @PathVariable("name") String name) {
        return tenants.resolveTenantId(authentication, exchange)
                .flatMap(t -> taxConfigService.deleteTemplate(t, name));
    }
}
