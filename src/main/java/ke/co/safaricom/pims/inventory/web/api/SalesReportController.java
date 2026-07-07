package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.SalesReportService;
import ke.co.safaricom.pims.inventory.web.model.SalesReportSchemas;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(Constants.API_PREFIX + "/sales")
@Tag(name = "Sales", description = "Sales analytics and reporting")
public class SalesReportController {

    private final SalesReportService salesReportService;
    private final TenantContextResolver tenants;

    public SalesReportController(SalesReportService salesReportService, TenantContextResolver tenants) {
        this.salesReportService = salesReportService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "Sales report aggregated by product and month")
    public Mono<SalesReportSchemas.SalesReportResponse> getSalesReport(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> salesReportService.getSalesReport(t, from, to, status, page, limit));
    }
}
