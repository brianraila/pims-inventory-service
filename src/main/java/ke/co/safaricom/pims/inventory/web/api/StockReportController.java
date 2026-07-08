package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.StockReportService;
import ke.co.safaricom.pims.inventory.web.model.StockReportSchemas;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(Constants.API_PREFIX + "/stock-report")
@Tag(name = "Stock", description = "Stock reporting")
public class StockReportController {

    private final StockReportService stockReportService;
    private final TenantContextResolver tenants;

    public StockReportController(StockReportService stockReportService, TenantContextResolver tenants) {
        this.stockReportService = stockReportService;
        this.tenants = tenants;
    }

    @GetMapping
    @Operation(summary = "Flat stock report by product and batch")
    public Mono<StockReportSchemas.StockReportResponse> getStockReport(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(name = "expiring_within_days", required = false) Integer expiringWithinDays) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> stockReportService.getStockReport(t, page, limit, search, status, expiringWithinDays));
    }
}
