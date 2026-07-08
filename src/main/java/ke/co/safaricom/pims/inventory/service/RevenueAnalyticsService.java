package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.web.model.RevenueAnalyticsSchemas;
import ke.co.safaricom.pims.inventory.web.util.ItemExtrasCodec;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Service
public class RevenueAnalyticsService {

    private static final String STATUS_SUBMITTED = "submitted";

    private final SalesInvoiceReportSupport support;
    private final InventoryService inventoryService;

    public RevenueAnalyticsService(SalesInvoiceReportSupport support, InventoryService inventoryService) {
        this.support = support;
        this.inventoryService = inventoryService;
    }

    public Mono<RevenueAnalyticsSchemas.RevenueAnalyticsResponse> getRevenueAnalytics(
            String tenantId, Integer days, String from, String to, String date) {
        SalesInvoiceReportSupport.DateRange range =
                SalesInvoiceReportSupport.resolveDateRange(days, from, to, date);
        SalesInvoiceReportSupport.DateRange previous = SalesInvoiceReportSupport.previousPeriod(range);

        return loadPeriodData(tenantId, range)
                .zipWith(loadPeriodData(tenantId, previous))
                .map(tuple -> {
                    PeriodMetrics current = metricsOf(tuple.getT1());
                    PeriodMetrics prior = metricsOf(tuple.getT2());
                    String currency = current.currency();

                    SalesInvoiceReportSupport.TrendResult revTrend =
                            SalesInvoiceReportSupport.computeTrend(current.totalRevenue(), prior.totalRevenue());
                    SalesInvoiceReportSupport.TrendResult gpTrend =
                            SalesInvoiceReportSupport.computeTrend(current.grossProfit(), prior.grossProfit());
                    SalesInvoiceReportSupport.TrendResult npTrend =
                            SalesInvoiceReportSupport.computeTrend(current.netProfit(), prior.netProfit());
                    SalesInvoiceReportSupport.TrendResult marginTrend =
                            SalesInvoiceReportSupport.computeTrend(current.margin(), prior.margin());

                    return new RevenueAnalyticsSchemas.RevenueAnalyticsResponse(
                            new RevenueAnalyticsSchemas.MoneyMetricCard(
                                    round2(current.totalRevenue()), currency, revTrend.changePct(), revTrend.trend()),
                            new RevenueAnalyticsSchemas.MoneyMetricCard(
                                    round2(current.grossProfit()), currency, gpTrend.changePct(), gpTrend.trend()),
                            new RevenueAnalyticsSchemas.MoneyMetricCard(
                                    round2(current.netProfit()), currency, npTrend.changePct(), npTrend.trend()),
                            new RevenueAnalyticsSchemas.PercentMetricCard(
                                    round2(current.margin()), "%", marginTrend.changePct(), marginTrend.trend()));
                });
    }

    public Mono<RevenueAnalyticsSchemas.RevenueTrendsResponse> getRevenueTrends(
            String tenantId, Integer days, String from, String to, String date, String metric) {
        SalesInvoiceReportSupport.DateRange range =
                SalesInvoiceReportSupport.resolveDateRange(days, from, to, date);
        String safeMetric = normalizeMetric(metric);

        return loadPeriodData(tenantId, range).map(data -> {
            Map<String, DailyAgg> byDay = new TreeMap<>();
            Map<String, String> invoiceDates = invoiceDates(data.invoices());

            for (Map<String, Object> row : data.itemRows()) {
                String parent = Objects.toString(row.get("parent"), null);
                String postingDate = invoiceDates.get(parent);
                if (!StringUtils.hasText(postingDate)) continue;

                double qty = SalesInvoiceReportSupport.toDouble(row.get("qty"));
                double amount = SalesInvoiceReportSupport.toDouble(row.get("amount"));
                String itemCode = Objects.toString(row.get("item_code"), "");
                double cost = qty * unitCost(data.unitCosts(), itemCode);

                DailyAgg agg = byDay.computeIfAbsent(postingDate, ignored -> new DailyAgg());
                agg.revenue += amount;
                agg.profit += amount - cost;
                agg.units += Math.round(qty);
            }

            // Fill missing days in range with zeros so charts have contiguous series
            LocalDate cursor = LocalDate.parse(range.from());
            LocalDate end = LocalDate.parse(range.to());
            List<RevenueAnalyticsSchemas.TrendSeriesPoint> series = new ArrayList<>();
            while (!cursor.isAfter(end)) {
                String key = cursor.toString();
                DailyAgg agg = byDay.getOrDefault(key, DailyAgg.EMPTY);
                series.add(new RevenueAnalyticsSchemas.TrendSeriesPoint(
                        key, round2(agg.revenue), round2(agg.profit), agg.units));
                cursor = cursor.plusDays(1);
            }
            return new RevenueAnalyticsSchemas.RevenueTrendsResponse(safeMetric, series);
        });
    }

    public Mono<RevenueAnalyticsSchemas.RevenueByCategoryResponse> getRevenueByCategory(
            String tenantId, Integer days, String from, String to, String date, int limit) {
        SalesInvoiceReportSupport.DateRange range =
                SalesInvoiceReportSupport.resolveDateRange(days, from, to, date);
        int safeLimit = Math.max(1, Math.min(limit, 100));

        return loadPeriodData(tenantId, range).map(data -> {
            Map<String, Double> byCategory = new HashMap<>();
            for (Map<String, Object> row : data.itemRows()) {
                String itemCode = Objects.toString(row.get("item_code"), null);
                if (!StringUtils.hasText(itemCode)) continue;
                double amount = SalesInvoiceReportSupport.toDouble(row.get("amount"));
                String category = categoryOf(data.itemsByCode().get(itemCode));
                byCategory.merge(category, amount, Double::sum);
            }

            List<RevenueAnalyticsSchemas.CategoryRevenue> categories = byCategory.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(safeLimit)
                    .map(e -> new RevenueAnalyticsSchemas.CategoryRevenue(e.getKey(), round2(e.getValue())))
                    .toList();

            double total = byCategory.values().stream().mapToDouble(Double::doubleValue).sum();
            return new RevenueAnalyticsSchemas.RevenueByCategoryResponse(
                    round2(total), data.currency(), categories);
        });
    }

    public Mono<RevenueAnalyticsSchemas.TopSellingProductsResponse> getTopSellingProducts(
            String tenantId, Integer days, String from, String to, String date, int limit) {
        SalesInvoiceReportSupport.DateRange range =
                SalesInvoiceReportSupport.resolveDateRange(days, from, to, date);
        SalesInvoiceReportSupport.DateRange previous = SalesInvoiceReportSupport.previousPeriod(range);
        int safeLimit = Math.max(1, Math.min(limit, 100));

        return loadPeriodData(tenantId, range)
                .zipWith(loadPeriodData(tenantId, previous))
                .map(tuple -> {
                    PeriodData current = tuple.getT1();
                    PeriodData prior = tuple.getT2();
                    Map<String, ProductAgg> currentAggs = productAggregates(current);
                    Map<String, ProductAgg> priorAggs = productAggregates(prior);

                    List<Map.Entry<String, ProductAgg>> ranked = currentAggs.entrySet().stream()
                            .sorted(Comparator
                                    .<Map.Entry<String, ProductAgg>>comparingDouble(e -> e.getValue().units)
                                    .reversed()
                                    .thenComparing(e -> e.getValue().revenue, Comparator.reverseOrder()))
                            .limit(safeLimit)
                            .toList();

                    List<RevenueAnalyticsSchemas.TopProduct> products = new ArrayList<>();
                    int rank = 1;
                    for (Map.Entry<String, ProductAgg> entry : ranked) {
                        String itemCode = entry.getKey();
                        ProductAgg agg = entry.getValue();
                        InventoryItemResponse item = current.itemsByCode().get(itemCode);
                        String name = item != null ? item.name() : itemCode;
                        long priorUnits = priorAggs.containsKey(itemCode) ? priorAggs.get(itemCode).units : 0L;
                        String trend = SalesInvoiceReportSupport.computeTrend(agg.units, priorUnits).trend();
                        products.add(new RevenueAnalyticsSchemas.TopProduct(
                                rank++,
                                StableEntityIds.itemId(tenantId, itemCode),
                                name,
                                agg.units,
                                round2(agg.revenue),
                                current.currency(),
                                trend));
                    }
                    return new RevenueAnalyticsSchemas.TopSellingProductsResponse(products);
                });
    }

    public Mono<RevenueAnalyticsSchemas.RevenueByPaymentMethodResponse> getRevenueByPaymentMethod(
            String tenantId, Integer days, String from, String to, String date) {
        SalesInvoiceReportSupport.DateRange range =
                SalesInvoiceReportSupport.resolveDateRange(days, from, to, date);
        SalesInvoiceReportSupport.DateRange previous = SalesInvoiceReportSupport.previousPeriod(range);

        return loadPeriodData(tenantId, range)
                .zipWith(loadPeriodData(tenantId, previous))
                .flatMap(tuple -> {
                    PeriodData current = tuple.getT1();
                    PeriodData prior = tuple.getT2();
                    List<String> currentNames = invoiceNames(current.invoices());
                    List<String> priorNames = invoiceNames(prior.invoices());
                    return support.fetchPaymentEntriesForInvoices(tenantId, currentNames)
                            .zipWith(support.fetchPaymentEntriesForInvoices(tenantId, priorNames))
                            .map(payments -> {
                                Map<String, Double> currentByMethod = paymentTotals(payments.getT1());
                                Map<String, Double> priorByMethod = paymentTotals(payments.getT2());

                                // Also attribute unpaid / unpaid-via-PE invoices as "unknown"
                                double attributed = currentByMethod.values().stream().mapToDouble(Double::doubleValue).sum();
                                double invoiceTotal = current.invoices().stream()
                                        .mapToDouble(i -> i.grandTotal() != null ? i.grandTotal() : 0)
                                        .sum();
                                if (invoiceTotal > attributed + 0.01) {
                                    currentByMethod.merge("unknown", invoiceTotal - attributed, Double::sum);
                                }

                                double total = currentByMethod.values().stream().mapToDouble(Double::doubleValue).sum();
                                List<RevenueAnalyticsSchemas.PaymentMethodBreakdown> methods =
                                        currentByMethod.entrySet().stream()
                                                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                                                .map(e -> {
                                                    double value = e.getValue();
                                                    double pct = total > 0 ? round2((value / total) * 100.0) : 0.0;
                                                    double priorValue = priorByMethod.getOrDefault(e.getKey(), 0.0);
                                                    SalesInvoiceReportSupport.TrendResult trend =
                                                            SalesInvoiceReportSupport.computeTrend(value, priorValue);
                                                    return new RevenueAnalyticsSchemas.PaymentMethodBreakdown(
                                                            e.getKey(),
                                                            round2(value),
                                                            pct,
                                                            trend.changePct(),
                                                            trend.trend());
                                                })
                                                .toList();

                                return new RevenueAnalyticsSchemas.RevenueByPaymentMethodResponse(
                                        round2(total), current.currency(), methods);
                            });
                });
    }

    public Mono<RevenueAnalyticsSchemas.TransactionTypesResponse> getTransactionTypes(
            String tenantId, Integer days, String from, String to, String date) {
        SalesInvoiceReportSupport.DateRange range =
                SalesInvoiceReportSupport.resolveDateRange(days, from, to, date);

        return support.fetchInvoicesWithSaleMetadata(tenantId, range.from(), range.to(), STATUS_SUBMITTED, true)
                .map(invoices -> {
                    Map<String, long[]> byMonth = new TreeMap<>();
                    for (ErpNextDoc invoice : invoices) {
                        String month = SalesInvoiceReportSupport.toPeriod(invoice.postingDate());
                        if (month == null) continue;
                        long[] counts = byMonth.computeIfAbsent(month, ignored -> new long[2]);
                        if (SalesInvoiceReportSupport.isPrescriptionSale(invoice)) {
                            counts[1]++;
                        } else {
                            counts[0]++;
                        }
                    }
                    List<RevenueAnalyticsSchemas.TransactionTypeMonth> data = byMonth.entrySet().stream()
                            .map(e -> new RevenueAnalyticsSchemas.TransactionTypeMonth(
                                    e.getKey(), e.getValue()[0], e.getValue()[1]))
                            .toList();
                    return new RevenueAnalyticsSchemas.TransactionTypesResponse(data);
                });
    }

    public Mono<RevenueAnalyticsSchemas.RevenueOverviewResponse> getRevenueOverview(
            String tenantId,
            Integer days,
            String from,
            String to,
            String date,
            int page,
            int pageSize,
            String category) {
        SalesInvoiceReportSupport.DateRange range =
                SalesInvoiceReportSupport.resolveDateRange(days, from, to, date);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));

        return loadPeriodData(tenantId, range).map(data -> {
            Map<String, String> invoiceDates = invoiceDates(data.invoices());
            Map<OverviewKey, ProductAgg> aggregates = new HashMap<>();

            for (Map<String, Object> row : data.itemRows()) {
                String parent = Objects.toString(row.get("parent"), null);
                String itemCode = Objects.toString(row.get("item_code"), null);
                if (!StringUtils.hasText(parent) || !StringUtils.hasText(itemCode)) continue;
                String postingDate = invoiceDates.get(parent);
                String period = SalesInvoiceReportSupport.toPeriod(postingDate);
                if (period == null) continue;

                InventoryItemResponse item = data.itemsByCode().get(itemCode);
                String itemCategory = categoryOf(item);
                if (StringUtils.hasText(category) && !category.equalsIgnoreCase(itemCategory)) {
                    continue;
                }

                double qty = SalesInvoiceReportSupport.toDouble(row.get("qty"));
                double amount = SalesInvoiceReportSupport.toDouble(row.get("amount"));
                OverviewKey key = new OverviewKey(period, itemCode);
                aggregates.computeIfAbsent(key, ignored -> new ProductAgg()).add(qty, amount);
            }

            List<RevenueAnalyticsSchemas.RevenueOverviewRow> rows = new ArrayList<>();
            for (Map.Entry<OverviewKey, ProductAgg> entry : aggregates.entrySet()) {
                OverviewKey key = entry.getKey();
                ProductAgg agg = entry.getValue();
                InventoryItemResponse item = data.itemsByCode().get(key.itemCode());
                String productName = item != null ? item.name() : key.itemCode();
                String subtitle = genericNameOf(item);
                String itemCategory = categoryOf(item);
                double avgPrice = agg.units > 0 ? agg.revenue / agg.units : 0.0;
                rows.add(new RevenueAnalyticsSchemas.RevenueOverviewRow(
                        key.period(),
                        StableEntityIds.itemId(tenantId, key.itemCode()),
                        productName,
                        subtitle,
                        itemCategory,
                        round2(agg.revenue),
                        data.currency(),
                        agg.units,
                        round2(avgPrice)));
            }

            rows.sort(Comparator
                    .comparing(RevenueAnalyticsSchemas.RevenueOverviewRow::period).reversed()
                    .thenComparing(RevenueAnalyticsSchemas.RevenueOverviewRow::revenue, Comparator.reverseOrder()));

            long totalRecords = rows.size();
            long totalPages = SalesInvoiceReportSupport.calcTotalPages(totalRecords, safeSize);
            int fromIdx = Math.max(0, (safePage - 1) * safeSize);
            int toIdx = Math.min(rows.size(), fromIdx + safeSize);
            List<RevenueAnalyticsSchemas.RevenueOverviewRow> pageRows =
                    fromIdx >= rows.size() ? List.of() : rows.subList(fromIdx, toIdx);

            return new RevenueAnalyticsSchemas.RevenueOverviewResponse(
                    safePage, safeSize, totalPages, totalRecords, pageRows);
        });
    }

    // ---- data loading ---------------------------------------------------------

    private Mono<PeriodData> loadPeriodData(String tenantId, SalesInvoiceReportSupport.DateRange range) {
        return support.fetchInvoices(tenantId, range.from(), range.to(), STATUS_SUBMITTED, true)
                .flatMap(invoices -> {
                    List<String> names = invoiceNames(invoices);
                    Mono<List<Map<String, Object>>> itemsMono = support.fetchInvoiceItems(tenantId, names);
                    Mono<List<InventoryItemResponse>> catalogMono = inventoryService.listItems(tenantId);
                    return itemsMono.zipWith(catalogMono).map(tuple -> {
                        Map<String, InventoryItemResponse> byCode = new HashMap<>();
                        for (InventoryItemResponse item : tuple.getT2()) {
                            byCode.put(item.id(), item);
                        }
                        Map<String, Double> unitCosts = averageUnitCosts(tuple.getT2());
                        String currency = invoices.stream()
                                .map(ErpNextDoc::currency)
                                .filter(StringUtils::hasText)
                                .findFirst()
                                .orElse(SalesInvoiceReportSupport.CURRENCY);
                        return new PeriodData(invoices, tuple.getT1(), byCode, unitCosts, currency);
                    });
                });
    }

    // ---- aggregations ---------------------------------------------------------

    private static PeriodMetrics metricsOf(PeriodData data) {
        double totalRevenue = data.invoices().stream()
                .mapToDouble(i -> i.grandTotal() != null ? i.grandTotal() : 0)
                .sum();
        double taxes = data.invoices().stream()
                .mapToDouble(i -> i.totalTaxesAndCharges() != null ? i.totalTaxesAndCharges() : 0)
                .sum();

        double lineRevenue = 0;
        double cogs = 0;
        for (Map<String, Object> row : data.itemRows()) {
            double qty = SalesInvoiceReportSupport.toDouble(row.get("qty"));
            double amount = SalesInvoiceReportSupport.toDouble(row.get("amount"));
            String itemCode = Objects.toString(row.get("item_code"), "");
            lineRevenue += amount;
            cogs += qty * unitCost(data.unitCosts(), itemCode);
        }

        // Prefer invoice grand_total for headline revenue; use line amounts for COGS pairing.
        // If there are no invoices, fall back to line revenue.
        if (totalRevenue == 0 && lineRevenue > 0) {
            totalRevenue = lineRevenue;
        }
        double grossProfit = lineRevenue - cogs;
        double netProfit = grossProfit - taxes;
        double margin = totalRevenue > 0 ? (grossProfit / totalRevenue) * 100.0 : 0.0;
        return new PeriodMetrics(totalRevenue, grossProfit, netProfit, margin, data.currency());
    }

    private static Map<String, ProductAgg> productAggregates(PeriodData data) {
        Map<String, ProductAgg> out = new HashMap<>();
        for (Map<String, Object> row : data.itemRows()) {
            String itemCode = Objects.toString(row.get("item_code"), null);
            if (!StringUtils.hasText(itemCode)) continue;
            out.computeIfAbsent(itemCode, ignored -> new ProductAgg())
                    .add(SalesInvoiceReportSupport.toDouble(row.get("qty")),
                            SalesInvoiceReportSupport.toDouble(row.get("amount")));
        }
        return out;
    }

    private static Map<String, Double> paymentTotals(List<ErpNextDoc> payments) {
        Map<String, Double> byMethod = new LinkedHashMap<>();
        for (ErpNextDoc pe : payments) {
            String method = SalesInvoiceReportSupport.normalizePaymentMethod(pe.modeOfPayment());
            double amount = pe.paidAmount() != null ? pe.paidAmount() : 0;
            byMethod.merge(method, amount, Double::sum);
        }
        return byMethod;
    }

    private static Map<String, Double> averageUnitCosts(List<InventoryItemResponse> items) {
        Map<String, Double> costs = new HashMap<>();
        for (InventoryItemResponse item : items) {
            if (item.batches() == null || item.batches().isEmpty()) {
                costs.put(item.id(), 0.0);
                continue;
            }
            double sum = 0;
            int count = 0;
            for (BatchResponse batch : item.batches()) {
                if (batch.cost() > 0) {
                    sum += batch.cost();
                    count++;
                }
            }
            costs.put(item.id(), count > 0 ? sum / count : 0.0);
        }
        return costs;
    }

    private static double unitCost(Map<String, Double> unitCosts, String itemCode) {
        return unitCosts.getOrDefault(itemCode, 0.0);
    }

    private static String categoryOf(InventoryItemResponse item) {
        if (item == null) return "Uncategorized";
        Map<String, Object> extras = item.pimsCustomColumns() != null ? item.pimsCustomColumns() : Map.of();
        if (extras.containsKey("category") && extras.get("category") != null) {
            return Objects.toString(extras.get("category"), item.category());
        }
        return StringUtils.hasText(item.category()) ? item.category() : "Uncategorized";
    }

    private static String genericNameOf(InventoryItemResponse item) {
        if (item == null) return "";
        Map<String, Object> extras = item.pimsCustomColumns() != null ? item.pimsCustomColumns() : Map.of();
        String fromExtras = ItemExtrasCodec.displayGenericName(item.genericName(), extras);
        if (StringUtils.hasText(fromExtras)) return fromExtras;
        return StringUtils.hasText(item.genericName()) ? item.genericName() : "";
    }

    private static List<String> invoiceNames(List<ErpNextDoc> invoices) {
        List<String> names = new ArrayList<>();
        for (ErpNextDoc inv : invoices) {
            if (StringUtils.hasText(inv.name())) {
                names.add(inv.name());
            }
        }
        return names;
    }

    private static Map<String, String> invoiceDates(List<ErpNextDoc> invoices) {
        Map<String, String> dates = new HashMap<>();
        for (ErpNextDoc inv : invoices) {
            if (StringUtils.hasText(inv.name()) && StringUtils.hasText(inv.postingDate())) {
                dates.put(inv.name(), inv.postingDate());
            }
        }
        return dates;
    }

    private static String normalizeMetric(String metric) {
        if (!StringUtils.hasText(metric)) return "revenue";
        String n = metric.trim().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "profit", "units", "revenue" -> n;
            default -> "revenue";
        };
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ---- records --------------------------------------------------------------

    private record PeriodData(
            List<ErpNextDoc> invoices,
            List<Map<String, Object>> itemRows,
            Map<String, InventoryItemResponse> itemsByCode,
            Map<String, Double> unitCosts,
            String currency) {}

    private record PeriodMetrics(
            double totalRevenue,
            double grossProfit,
            double netProfit,
            double margin,
            String currency) {}

    private static final class ProductAgg {
        private long units;
        private double revenue;

        private void add(double qty, double amount) {
            units += Math.round(qty);
            revenue += amount;
        }
    }

    private static final class DailyAgg {
        private static final DailyAgg EMPTY = new DailyAgg();
        private double revenue;
        private double profit;
        private long units;
    }

    private record OverviewKey(String period, String itemCode) {}
}
