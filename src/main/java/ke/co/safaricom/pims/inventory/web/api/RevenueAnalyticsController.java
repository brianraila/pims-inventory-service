package ke.co.safaricom.pims.inventory.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.co.safaricom.pims.inventory.Constants;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.RevenueAnalyticsService;
import ke.co.safaricom.pims.inventory.web.model.RevenueAnalyticsSchemas;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(Constants.API_PREFIX + "/reports")
@Tag(name = "Reports", description = "Revenue and sales analytics")
public class RevenueAnalyticsController {

    private final RevenueAnalyticsService revenueAnalyticsService;
    private final TenantContextResolver tenants;

    public RevenueAnalyticsController(
            RevenueAnalyticsService revenueAnalyticsService, TenantContextResolver tenants) {
        this.revenueAnalyticsService = revenueAnalyticsService;
        this.tenants = tenants;
    }

    @GetMapping("/revenue-analytics")
    @Operation(summary = "KPI cards: total revenue, gross/net profit, margin with period-over-period change")
    public Mono<RevenueAnalyticsSchemas.RevenueAnalyticsResponse> revenueAnalytics(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Integer days) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> revenueAnalyticsService.getRevenueAnalytics(t, days, from, to, date));
    }

    @GetMapping("/revenue-trends")
    @Operation(summary = "Daily revenue/profit/units series for charts")
    public Mono<RevenueAnalyticsSchemas.RevenueTrendsResponse> revenueTrends(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "revenue") String metric) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> revenueAnalyticsService.getRevenueTrends(t, days, from, to, date, metric));
    }

    @GetMapping("/revenue-by-category")
    @Operation(summary = "Top categories by sales revenue")
    public Mono<RevenueAnalyticsSchemas.RevenueByCategoryResponse> revenueByCategory(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "5") int limit) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> revenueAnalyticsService.getRevenueByCategory(t, days, from, to, date, limit));
    }

    @GetMapping("/top-selling-products")
    @Operation(summary = "Top products by units sold with revenue and trend")
    public Mono<RevenueAnalyticsSchemas.TopSellingProductsResponse> topSellingProducts(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "5") int limit) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> revenueAnalyticsService.getTopSellingProducts(t, days, from, to, date, limit));
    }

    @GetMapping("/revenue-by-payment-method")
    @Operation(summary = "Revenue breakdown by payment method with share and trend")
    public Mono<RevenueAnalyticsSchemas.RevenueByPaymentMethodResponse> revenueByPaymentMethod(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Integer days) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> revenueAnalyticsService.getRevenueByPaymentMethod(t, days, from, to, date));
    }

    @GetMapping("/transaction-types")
    @Operation(summary = "Monthly OTC vs prescription transaction counts")
    public Mono<RevenueAnalyticsSchemas.TransactionTypesResponse> transactionTypes(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Integer days) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> revenueAnalyticsService.getTransactionTypes(t, days, from, to, date));
    }

    @GetMapping("/revenue-overview")
    @Operation(summary = "Paginated product revenue table with average selling price")
    public Mono<RevenueAnalyticsSchemas.RevenueOverviewResponse> revenueOverview(
            Authentication auth,
            ServerWebExchange exchange,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "10") int pageSize,
            @RequestParam(required = false) String category) {
        return tenants.resolveTenantId(auth, exchange)
                .flatMap(t -> revenueAnalyticsService.getRevenueOverview(
                        t, days, from, to, date, page, pageSize, category));
    }
}
