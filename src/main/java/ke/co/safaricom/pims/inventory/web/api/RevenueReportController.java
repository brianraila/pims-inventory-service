package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.RevenueReportService;
import ke.co.safaricom.pims.inventory.web.model.RevenueReportSchemas;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(Constants.API_PREFIX + "/revenue")
@Tag(name = "Revenue", description = "Revenue dashboard metrics")
public class RevenueReportController {

    private final RevenueReportService revenueReportService;
    private final TenantContextResolver tenants;

    public RevenueReportController(RevenueReportService revenueReportService, TenantContextResolver tenants) {
        this.revenueReportService = revenueReportService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "Revenue dashboard KPIs with Rx/OTC split")
    public Mono<RevenueReportSchemas.RevenueReportResponse> getRevenueReport(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> revenueReportService.getRevenueReport(t, days, from, to, status));
    }
}
