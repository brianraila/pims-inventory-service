package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.model.SalesReportSchemas;
import ke.co.safaricom.pims.inventory.web.util.ItemExtrasCodec;
import ke.co.safaricom.pims.inventory.web.util.StableEntityIds;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SalesReportService {

    private final SalesInvoiceReportSupport support;
    private final InventoryService inventoryService;

    public SalesReportService(SalesInvoiceReportSupport support, InventoryService inventoryService) {
        this.support = support;
        this.inventoryService = inventoryService;
    }

    public Mono<SalesReportSchemas.SalesReportResponse> getSalesReport(
            String tenantId, String from, String to, String status, int page, int limit) {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(limit, 100));

        return support.fetchInvoices(tenantId, from, to, status, false)
                .flatMap(invoices -> {
                    if (invoices.isEmpty()) {
                        return Mono.just(emptyResponse(safePage, safeLimit));
                    }
                    Map<String, InvoiceMeta> invoiceMeta = new HashMap<>();
                    for (ErpNextDoc inv : invoices) {
                        invoiceMeta.put(inv.name(), new InvoiceMeta(
                                inv.postingDate(),
                                inv.currency() != null ? inv.currency() : SalesInvoiceReportSupport.CURRENCY));
                    }
                    return support.fetchInvoiceItems(tenantId, new ArrayList<>(invoiceMeta.keySet()))
                            .zipWith(inventoryService.listItems(tenantId))
                            .map(tuple -> buildResponse(
                                    tenantId,
                                    tuple.getT1(),
                                    invoiceMeta,
                                    tuple.getT2(),
                                    safePage,
                                    safeLimit));
                });
    }

    private SalesReportSchemas.SalesReportResponse buildResponse(
            String tenantId,
            List<Map<String, Object>> itemRows,
            Map<String, InvoiceMeta> invoiceMeta,
            List<InventoryItemResponse> items,
            int page,
            int limit) {
        Map<String, InventoryItemResponse> itemsByCode = new HashMap<>();
        for (InventoryItemResponse item : items) {
            itemsByCode.put(item.id(), item);
        }

        Map<AggregateKey, AggregateValue> aggregates = new HashMap<>();
        for (Map<String, Object> row : itemRows) {
            String parent = Objects.toString(row.get("parent"), null);
            String itemCode = Objects.toString(row.get("item_code"), null);
            if (!StringUtils.hasText(parent) || !StringUtils.hasText(itemCode)) continue;

            InvoiceMeta meta = invoiceMeta.get(parent);
            if (meta == null || !StringUtils.hasText(meta.postingDate())) continue;

            String period = SalesInvoiceReportSupport.toPeriod(meta.postingDate());
            if (period == null) continue;

            double qty = SalesInvoiceReportSupport.toDouble(row.get("qty"));
            double amount = SalesInvoiceReportSupport.toDouble(row.get("amount"));
            String currency = meta.currency() != null ? meta.currency() : SalesInvoiceReportSupport.CURRENCY;

            AggregateKey key = new AggregateKey(period, itemCode);
            aggregates.computeIfAbsent(key, ignored -> new AggregateValue(currency))
                    .add(qty, amount);
        }

        List<SalesReportSchemas.SalesRow> rows = new ArrayList<>();
        for (Map.Entry<AggregateKey, AggregateValue> entry : aggregates.entrySet()) {
            AggregateKey key = entry.getKey();
            AggregateValue value = entry.getValue();
            InventoryItemResponse item = itemsByCode.get(key.itemCode());
            Map<String, Object> extras = item != null && item.pimsCustomColumns() != null
                    ? item.pimsCustomColumns()
                    : Map.of();

            String productName = item != null ? item.name() : key.itemCode();
            String genericName = "";
            if (item != null) {
                String fromExtras = ItemExtrasCodec.displayGenericName(item.genericName(), extras);
                genericName = StringUtils.hasText(fromExtras)
                        ? fromExtras
                        : (StringUtils.hasText(item.genericName()) ? item.genericName() : "");
            }
            String category = item != null
                    ? (extras.containsKey("category")
                            ? Objects.toString(extras.get("category"), item.category())
                            : item.category())
                    : "";

            rows.add(new SalesReportSchemas.SalesRow(
                    key.period(),
                    StableEntityIds.itemId(tenantId, key.itemCode()),
                    productName,
                    genericName,
                    category,
                    Math.round(value.units()),
                    value.revenue(),
                    value.currency()));
        }

        rows.sort(Comparator
                .comparing(SalesReportSchemas.SalesRow::period).reversed()
                .thenComparing(SalesReportSchemas.SalesRow::productName, String.CASE_INSENSITIVE_ORDER));

        double totalRevenue = rows.stream().mapToDouble(SalesReportSchemas.SalesRow::revenue).sum();
        long totalUnits = rows.stream().mapToLong(SalesReportSchemas.SalesRow::unitsSold).sum();
        String summaryCurrency = rows.isEmpty() ? SalesInvoiceReportSupport.CURRENCY : rows.get(0).currency();

        long total = rows.size();
        int from = Math.max(0, (page - 1) * limit);
        int to = Math.min(rows.size(), from + limit);
        List<SalesReportSchemas.SalesRow> pageRows = from >= rows.size() ? List.of() : rows.subList(from, to);

        InventoryApiSchemas.Pagination pagination = new InventoryApiSchemas.Pagination(
                page, limit, total, SalesInvoiceReportSupport.calcTotalPages(total, limit));
        SalesReportSchemas.SalesSummary summary =
                new SalesReportSchemas.SalesSummary(totalRevenue, totalUnits, summaryCurrency);

        return new SalesReportSchemas.SalesReportResponse(pageRows, pagination, summary);
    }

    private static SalesReportSchemas.SalesReportResponse emptyResponse(int page, int limit) {
        InventoryApiSchemas.Pagination pagination =
                new InventoryApiSchemas.Pagination(page, limit, 0, 0);
        SalesReportSchemas.SalesSummary summary =
                new SalesReportSchemas.SalesSummary(0.0, 0L, SalesInvoiceReportSupport.CURRENCY);
        return new SalesReportSchemas.SalesReportResponse(List.of(), pagination, summary);
    }

    private record InvoiceMeta(String postingDate, String currency) {}

    private record AggregateKey(String period, String itemCode) {}

    private static final class AggregateValue {
        private double units;
        private double revenue;
        private final String currency;

        private AggregateValue(String currency) {
            this.currency = currency;
        }

        private void add(double qty, double amount) {
            units += qty;
            revenue += amount;
        }

        private double units() {
            return units;
        }

        private double revenue() {
            return revenue;
        }

        private String currency() {
            return currency;
        }
    }
}
