package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.BatchResponse;
import ke.co.safaricom.pims.inventory.api.dto.InventoryItemResponse;
import ke.co.safaricom.pims.inventory.web.model.Enums;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import ke.co.safaricom.pims.inventory.web.model.StockReportSchemas;
import ke.co.safaricom.pims.inventory.web.util.ItemExtrasCodec;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StockReportService {

    private record PendingRow(String itemCode, StockReportSchemas.StockReportRow row) {}

    private final InventoryService inventoryService;

    public StockReportService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    public Mono<StockReportSchemas.StockReportResponse> getStockReport(
            String tenantId, int page, int limit, String search, String status, Integer expiringWithinDays) {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(limit, 100));

        return Mono.zip(inventoryService.listItems(tenantId), inventoryService.listBatches(tenantId))
                .flatMap(tuple -> {
                    List<InventoryItemResponse> items = tuple.getT1();
                    List<BatchResponse> batches = tuple.getT2();
                    List<String> itemCodes = items.stream().map(InventoryItemResponse::id).toList();
                    return inventoryService.getStockLevels(tenantId, itemCodes, inventoryService.defaultWarehouse())
                            .flatMap(stockLevels -> buildRows(items, batches, stockLevels)
                                    .flatMap(rows -> applyFilters(rows, search, status, expiringWithinDays))
                                    .flatMap(filtered -> enrichOutOfStockDates(tenantId, filtered))
                                    .map(enriched -> paginate(enriched, safePage, safeLimit)));
                });
    }

    private Mono<List<PendingRow>> buildRows(
            List<InventoryItemResponse> items,
            List<BatchResponse> batches,
            Map<String, Double> stockLevels) {
        Map<String, List<BatchResponse>> byItem = new HashMap<>();
        for (BatchResponse batch : batches) {
            byItem.computeIfAbsent(batch.productId(), ignored -> new ArrayList<>()).add(batch);
        }

        List<PendingRow> rows = new ArrayList<>();
        for (InventoryItemResponse item : items) {
            List<BatchResponse> itemBatches = byItem.getOrDefault(item.id(), List.of());
            Map<String, Object> extras = mergedExtras(item);
            double reorder = extras.containsKey("reorder_level")
                    ? toDouble(extras.get("reorder_level"))
                    : item.reorderLevel();
            Enums.UnitOfMeasure uom = extras.containsKey("unit_of_measure")
                    ? ItemExtrasCodec.uom(extras.get("unit_of_measure").toString())
                    : Enums.UnitOfMeasure.fromItemUom(item.unit());

            if (itemBatches.isEmpty()) {
                double available = stockLevels.getOrDefault(item.id(), 0.0);
                rows.add(new PendingRow(item.id(), new StockReportSchemas.StockReportRow(
                        item.name(),
                        null,
                        available,
                        uom,
                        null,
                        0,
                        null,
                        available > 0 ? "active" : "out_of_stock",
                        null,
                        reorder,
                        0)));
                continue;
            }

            for (BatchResponse batch : itemBatches) {
                double unitPrice = batch.cost() > 0 ? batch.cost() : batch.tradeCost();
                double stockValue = batch.quantity() * unitPrice;
                rows.add(new PendingRow(item.id(), new StockReportSchemas.StockReportRow(
                        item.name(),
                        batch.batchNumber(),
                        batch.quantity(),
                        uom,
                        unitPrice > 0 ? unitPrice : null,
                        stockValue,
                        batch.expiryDate(),
                        mapBatchStatus(batch.status(), batch.quantity()),
                        null,
                        reorder,
                        0)));
            }
        }
        rows.sort(Comparator
                .comparing((PendingRow r) -> r.row().productName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.row().batchNumber() != null ? r.row().batchNumber() : ""));
        return Mono.just(rows);
    }

    private Mono<List<PendingRow>> applyFilters(
            List<PendingRow> rows,
            String search,
            String status,
            Integer expiringWithinDays) {
        List<PendingRow> filtered = new ArrayList<>();
        LocalDate expiringCutoff = expiringWithinDays != null && expiringWithinDays > 0
                ? LocalDate.now().plusDays(expiringWithinDays)
                : null;

        for (PendingRow pending : rows) {
            StockReportSchemas.StockReportRow row = pending.row();
            if (StringUtils.hasText(search)) {
                String q = search.trim().toLowerCase();
                boolean matches = row.productName().toLowerCase().contains(q)
                        || (row.batchNumber() != null && row.batchNumber().toLowerCase().contains(q));
                if (!matches) continue;
            }
            if (StringUtils.hasText(status) && !status.equalsIgnoreCase(row.status())) {
                continue;
            }
            if (expiringCutoff != null && StringUtils.hasText(row.expiryDate())) {
                try {
                    LocalDate expiry = LocalDate.parse(row.expiryDate());
                    if (expiry.isAfter(expiringCutoff)) continue;
                } catch (Exception ignored) {
                    continue;
                }
            }
            filtered.add(pending);
        }
        return Mono.just(filtered);
    }

    private Mono<List<StockReportSchemas.StockReportRow>> enrichOutOfStockDates(
            String tenantId, List<PendingRow> rows) {
        Map<String, String> ledgerCache = new HashMap<>();
        return Flux.fromIterable(rows)
                .concatMap(pending -> {
                    StockReportSchemas.StockReportRow row = pending.row();
                    if (row.availableStock() > 0) {
                        return Mono.just(row);
                    }
                    String cacheKey = pending.itemCode() + "|" + Objects.toString(row.batchNumber(), "");
                    if (ledgerCache.containsKey(cacheKey)) {
                        return Mono.just(withOutOfStock(row, ledgerCache.get(cacheKey)));
                    }
                    return inventoryService.findLastOutOfStockDate(tenantId, pending.itemCode(), row.batchNumber())
                            .defaultIfEmpty("")
                            .map(date -> {
                                ledgerCache.put(cacheKey, date);
                                return withOutOfStock(row, date);
                            });
                })
                .collectList();
    }

    private static StockReportSchemas.StockReportRow withOutOfStock(
            StockReportSchemas.StockReportRow row, String outOfStockDate) {
        if (!StringUtils.hasText(outOfStockDate)) {
            return row;
        }
        long days = 0;
        try {
            days = ChronoUnit.DAYS.between(LocalDate.parse(outOfStockDate), LocalDate.now());
            if (days < 0) days = 0;
        } catch (Exception ignored) {
            outOfStockDate = null;
        }
        return new StockReportSchemas.StockReportRow(
                row.productName(),
                row.batchNumber(),
                row.availableStock(),
                row.unitOfMeasure(),
                row.unitPrice(),
                row.stockValue(),
                row.expiryDate(),
                row.status(),
                outOfStockDate,
                row.reorderLevel(),
                days);
    }

    private StockReportSchemas.StockReportResponse paginate(
            List<StockReportSchemas.StockReportRow> rows, int page, int limit) {
        long total = rows.size();
        int from = Math.max(0, (page - 1) * limit);
        int to = Math.min(rows.size(), from + limit);
        List<StockReportSchemas.StockReportRow> slice = from >= rows.size() ? List.of() : rows.subList(from, to);
        InventoryApiSchemas.Pagination pagination = new InventoryApiSchemas.Pagination(
                page, limit, total, calcTotalPages(total, limit));
        return new StockReportSchemas.StockReportResponse(slice, pagination);
    }

    private static Map<String, Object> mergedExtras(InventoryItemResponse item) {
        Map<String, Object> merged = new HashMap<>();
        if (item.pimsCustomColumns() != null) {
            merged.putAll(item.pimsCustomColumns());
        }
        return merged;
    }

    private static String mapBatchStatus(String status, double quantity) {
        if (quantity <= 0) return "out_of_stock";
        if ("expired".equalsIgnoreCase(status)) return "expired";
        if ("recalled".equalsIgnoreCase(status)) return "recalled";
        return "active";
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return 0;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long calcTotalPages(long total, int limit) {
        if (limit <= 0) return 0;
        return (long) Math.ceil((double) total / (double) limit);
    }
}
