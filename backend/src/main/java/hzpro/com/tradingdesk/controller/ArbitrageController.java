package hzpro.com.tradingdesk.controller;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEventMarketsInfo;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.reports.service.DailyOpportunityStatsReport;
import hzpro.com.tradingdesk.arbitrage.reports.service.ExecutedOrdersReport;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventMarketsInfoRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import hzpro.com.tradingdesk.arbitrage.repository.OpportunityReportRepository;
import hzpro.com.tradingdesk.arbitrage.service.ArbitrageMonitorService;
import hzpro.com.tradingdesk.controller.dto.ActiveArbitrageEventDto;
import hzpro.com.tradingdesk.controller.dto.DailyOpportunityStatsDto;
import hzpro.com.tradingdesk.controller.dto.DailyOrderTotalDto;
import hzpro.com.tradingdesk.controller.dto.OpportunityReportAggregateDto;
import hzpro.com.tradingdesk.controller.dto.OpportunityReportDto;
import hzpro.com.tradingdesk.controller.dto.OrderDto;
import hzpro.com.tradingdesk.controller.dto.ProfitReportDto;
import hzpro.com.tradingdesk.similarmarkets.repository.SimilarMarketsRepository;
import lombok.RequiredArgsConstructor;


@RestController
@RequestMapping("/api/arbitrage")
@RequiredArgsConstructor
public class ArbitrageController {

    private final ArbitrageMonitorService monitorService;
    private final ArbitrageEventRepository eventRepository;
    private final SimilarMarketsRepository similarMarketsRepository;
    private final ArbitrageOrderRepository orderRepository;
    private final ArbitrageEventMarketsInfoRepository marketsInfoRepository;
    private final ObjectMapper objectMapper;
    private final ExecutedOrdersReport executedOrdersReport;
    private final DailyOpportunityStatsReport dailyOpportunityStatsReport;
    private final OpportunityReportRepository opportunityReportRepository;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("running", monitorService.isRunning());
        status.put("totalPairs", monitorService.getTotalPairs());
        status.put("activeOpportunities", monitorService.getActiveOpportunitiesCount());
        status.put("timestamp", java.time.Instant.now());
        return ResponseEntity.ok(status);
    }

    @PostMapping("/restart")
    public ResponseEntity<Map<String, Object>> restart() {
        monitorService.restart();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "restarted");
        response.put("running", monitorService.isRunning());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/events/active")
    public ResponseEntity<List<ActiveArbitrageEventDto>> getActiveEvents() {
        List<ArbitrageEvent> events = monitorService.getActiveOpportunities();
        return ResponseEntity.ok(enrich(events));
    }

    @GetMapping("/events/recent")
    public ResponseEntity<List<ActiveArbitrageEventDto>> getRecentEvents(
            @RequestParam(defaultValue = "50") int limit) {
        List<ArbitrageEvent> events = eventRepository.findUnclosedOpportunities()
                .stream()
                .limit(limit)
                .toList();
        return ResponseEntity.ok(enrich(events));
    }

    @GetMapping("/orders/{uuid}")
    public ResponseEntity<List<ArbitrageOrder>> getOrders(@PathVariable UUID uuid) {
        return ResponseEntity.ok(orderRepository.findByOpportunityUuid(uuid));
    }

    @GetMapping("/orders/stats")
    public ResponseEntity<Map<String, Object>> getOrdersStats() {
        List<ArbitrageOrder> allOrders = orderRepository.findAllByOrderByCreatedAtDesc();

        long totalOrders = allOrders.size();
        long executedOrders = allOrders.stream().filter(o -> o.getStatus() == OrderState.EXECUTED).count();
        long placedOrders = allOrders.stream().filter(o -> o.getStatus() == OrderState.PLACED).count();
        long canceledOrders = allOrders.stream().filter(o -> o.getStatus() == OrderState.CANCELED).count();
        long errorOrders = allOrders.stream().filter(o -> o.getStatus() == OrderState.ERROR).count();

        BigDecimal totalSpent = allOrders.stream()
                .filter(o -> o.getStatus() == OrderState.EXECUTED)
                .map(o -> o.getFilledAmount() != null ? o.getFilledAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalOrders", totalOrders);
        stats.put("executedOrders", executedOrders);
        stats.put("placedOrders", placedOrders);
        stats.put("canceledOrders", canceledOrders);
        stats.put("errorOrders", errorOrders);
        stats.put("totalSpent", totalSpent);
        stats.put("timestamp", Instant.now());

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/orders/recent")
    public ResponseEntity<List<OrderDto>> getRecentOrders(
            @RequestParam(defaultValue = "50") int limit) {
        List<ArbitrageOrder> orders = orderRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .limit(limit)
                .toList();
        return ResponseEntity.ok(toOrderDtos(orders));
    }

    @GetMapping("/orders/daily-totals")
    public ResponseEntity<List<DailyOrderTotalDto>> getDailyOrderTotals(
            @RequestParam(name = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDate effectiveEnd = endDate != null ? endDate : LocalDate.now(ZoneOffset.UTC);
        LocalDate effectiveStart = startDate != null ? startDate : effectiveEnd.minusDays(29);

        Map<LocalDate, BigDecimal> totals = executedOrdersReport.getExecutedOrdersByDate(effectiveStart, effectiveEnd);

        List<DailyOrderTotalDto> result = effectiveStart.datesUntil(effectiveEnd.plusDays(1))
                .map(date -> new DailyOrderTotalDto(date, totals.getOrDefault(date, BigDecimal.ZERO)))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/opportunities/daily-stats")
    public ResponseEntity<List<DailyOpportunityStatsDto>> getDailyOpportunityStats(
            @RequestParam(name = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDate effectiveEnd = endDate != null ? endDate : LocalDate.now(ZoneOffset.UTC);
        LocalDate effectiveStart = startDate != null ? startDate : effectiveEnd.minusDays(9);

        return ResponseEntity.ok(dailyOpportunityStatsReport.getDailyStats(effectiveStart, effectiveEnd));
    }

    @GetMapping("/orders")
    public ResponseEntity<Page<OrderDto>> getOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String platform) {

        OrderState orderStatus = null;
        if (status != null && !status.isEmpty()) {
            try {
                orderStatus = OrderState.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
            }
        }

        Page<ArbitrageOrder> ordersPage = orderRepository.findFiltered(
                orderStatus,
                platform,
                PageRequest.of(page, size));

        Map<UUID, Map<String, Object>> metaByUuid = buildMetaByUuid(ordersPage.getContent());
        Page<OrderDto> dtoPage = ordersPage.map(order -> toOrderDto(order, metaByUuid.get(order.getOpportunityUuid())));

        return ResponseEntity.ok(dtoPage);
    }

    private List<OrderDto> toOrderDtos(List<ArbitrageOrder> orders) {
        Map<UUID, Map<String, Object>> metaByUuid = buildMetaByUuid(orders);
        return orders.stream()
                .map(o -> toOrderDto(o, metaByUuid.get(o.getOpportunityUuid())))
                .toList();
    }

    private Map<UUID, Map<String, Object>> buildMetaByUuid(List<ArbitrageOrder> orders) {
        List<UUID> uuids = orders.stream()
                .map(ArbitrageOrder::getOpportunityUuid)
                .distinct()
                .toList();

        if (uuids.isEmpty()) return Map.of();

        return marketsInfoRepository.findByUuidIn(uuids).stream()
                .collect(Collectors.toMap(
                        ArbitrageEventMarketsInfo::getUuid,
                        info -> flattenMarketsInfo(info.getMarketsInfo()),
                        (a, _) -> a));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenMarketsInfo(String marketsInfoJson) {
        if (marketsInfoJson == null || marketsInfoJson.isBlank()) return Map.of();
        try {
            Map<String, Object> root = objectMapper.readValue(marketsInfoJson, Map.class);
            Map<String, Object> flat = new HashMap<>();
            for (var entry : root.entrySet()) {
                String platform = entry.getKey();
                if (entry.getValue() instanceof Map<?, ?> pm) {
                    String prefix = "POLYMARKET".equalsIgnoreCase(platform) ? "polymarket_" : "kalshi_";
                    for (var field : ((Map<String, Object>) pm).entrySet()) {
                        flat.put(prefix + camelToSnake(field.getKey()), field.getValue());
                    }
                }
            }
            return flat;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String camelToSnake(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private OrderDto toOrderDto(ArbitrageOrder order, Map<String, Object> meta) {
        boolean isPoly = "POLYMARKET".equalsIgnoreCase(order.getPlatform());
        return new OrderDto(
                order.getId(),
                order.getOpportunityUuid(),
                order.getPlatform(),
                order.getContractType(),
                order.getPrice(),
                order.getQuantity(),
                order.getOrderId(),
                order.getStatus().name(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getFilledQuantity(),
                order.getFilledAmount(),
                order.getExecutedAt(),
                meta != null ? (String) meta.get(isPoly ? "polymarket_event_ticker" : "kalshi_event_ticker") : null,
                meta != null ? (String) meta.get(isPoly ? "polymarket_event_title" : "kalshi_event_title") : null,
                meta != null ? (String) meta.get(isPoly ? "polymarket_market_title" : "kalshi_market_title") : null,
                meta != null ? (String) meta.get(isPoly ? "polymarket_market_ticker" : "kalshi_market_ticker") : null
        );
    }

    @GetMapping("/opportunities/report")
    public ResponseEntity<List<OpportunityReportDto>> getOpportunityReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @RequestParam(defaultValue = "true") boolean onlyWithOrders) {

        if (startDate == null) {
            startDate = Instant.now().minus(Duration.ofDays(7));
        }
        if (endDate == null) {
            endDate = Instant.now();
        }

        var rows = opportunityReportRepository.findOpportunityReport(startDate, endDate, onlyWithOrders);

        List<Long> ids = rows.stream()
                .map(OpportunityReportAggregateDto::similarMarketId)
                .distinct().toList();

        Map<Long, Map<String, Object>> meta = buildOpportunityReportMetadata(
                ids, startDate, endDate);

        return ResponseEntity.ok(rows.stream().map(r -> {
            long id = r.similarMarketId();
            Map<String, Object> m = meta.getOrDefault(id, Map.of());
            return new OpportunityReportDto(
                    id,
                    (String) m.get("polymarket_event_ticker"),
                    (String) m.get("polymarket_event_title"),
                    (String) m.get("polymarket_market_title"),
                    (String) m.get("polymarket_market_ticker"),
                    (String) m.get("kalshi_event_ticker"),
                    (String) m.get("kalshi_event_title"),
                    (String) m.get("kalshi_market_title"),
                    (String) m.get("kalshi_market_ticker"),
                    toInstant(m.get("polymarket_market_close_datetime")),
                    toInstant(m.get("kalshi_market_close_datetime")),
                    r.lastOpportunityAt(),
                    r.opportunityCount(),
                    r.oppsWithOrders(),
                    r.oppsWithUnexecuted(),
                    r.pmOrders(),
                    r.ksOrders(),
                    r.pmExecutedCount(),
                    r.ksExecutedCount(),
                    r.spreadBelowThresholdCloseCount(),
                    r.priceDataStaleCloseCount(),
                    r.priceDataUnavailableCloseCount(),
                    r.orderCreationFailedCloseCount(),
                    r.orderbookPriceBelowThresholdCloseCount(),
                    r.orderBookNotEnoughAmountCloseCount(),
                    r.orderbookEmptyCloseCount(),
                    r.minimumOrderCostNotMetCloseCount(),
                    r.activePairLimitReachedCloseCount(),
                    r.ordersCreatedCloseCount(),
                    r.unknownCloseCount()
            );
        }).toList());
    }

    private Map<Long, Map<String, Object>> buildOpportunityReportMetadata(
            List<Long> similarMarketIds,
            Instant startDate,
            Instant endDate) {
        if (similarMarketIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, UUID> latestUuidByPairId = new HashMap<>();
        eventRepository.findStartsForOpportunityReportMetadata(similarMarketIds, startDate, endDate)
                .forEach(event -> latestUuidByPairId.putIfAbsent(
                        event.getSimilarMarketId(), event.getUuid()));

        if (latestUuidByPairId.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> pairIdByUuid = latestUuidByPairId.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        Map<Long, Map<String, Object>> metadataByPairId = new HashMap<>();
        marketsInfoRepository.findByUuidIn(List.copyOf(pairIdByUuid.keySet())).forEach(info -> {
            Long pairId = pairIdByUuid.get(info.getUuid());
            if (pairId != null) {
                metadataByPairId.putIfAbsent(pairId, flattenReportMetadata(info));
            }
        });
        return metadataByPairId;
    }

    private Map<String, Object> flattenReportMetadata(ArbitrageEventMarketsInfo info) {
        Map<String, Object> metadata = new HashMap<>(flattenSimilarMarketsInfo(info.getSimilarMarketsInfo()));
        metadata.putAll(flattenMarketsInfo(info.getMarketsInfo()));
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenSimilarMarketsInfo(String similarMarketsInfoJson) {
        if (similarMarketsInfoJson == null || similarMarketsInfoJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(similarMarketsInfoJson, Map.class);
            Map<String, Object> flattened = new HashMap<>();
            root.forEach((key, value) -> flattened.put(camelToSnake(key), value));
            return flattened;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (java.time.format.DateTimeParseException ignored) {
                try {
                    return OffsetDateTime.parse(text).toInstant();
                } catch (java.time.format.DateTimeParseException ignoredAgain) {
                    return null;
                }
            }
        }
        return null;
    }

    @GetMapping("/opportunities/profit-report")
    public ResponseEntity<List<ProfitReportDto>> getProfitReport(
            @RequestParam(defaultValue = "POLYMARKET") String closeSource,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {

        Instant start = startDate != null ? startDate : Instant.now().minus(Duration.ofDays(30));
        Instant end = endDate != null ? endDate : Instant.now();

        List<Object[]> rows = eventRepository.findProfitReport(closeSource, start, end);

        return ResponseEntity.ok(rows.stream().map(r -> {
            BigDecimal orderTotal = toBigDecimal(r[3]).add(toBigDecimal(r[4]));
            BigDecimal filledTotal = toBigDecimal(r[5]).add(toBigDecimal(r[6]));
            long oppCount = ((Number) r[2]).longValue();
            long orderCount = oppCount;

            return new ProfitReportDto(
                    toInstant(r[0]),
                    ((Number) r[1]).longValue(),
                    oppCount,
                    orderTotal,
                    filledTotal,
                    orderTotal.compareTo(BigDecimal.ZERO) > 0
                            ? BigDecimal.valueOf(orderCount).subtract(orderTotal)
                            : BigDecimal.ZERO,
                    filledTotal.compareTo(BigDecimal.ZERO) > 0
                            ? BigDecimal.valueOf(orderCount).subtract(filledTotal)
                            : BigDecimal.ZERO
            );
        }).toList());
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    private List<ActiveArbitrageEventDto> enrich(List<ArbitrageEvent> events) {
        if (events.isEmpty()) return List.of();

        List<Long> ids = events.stream()
                .map(ArbitrageEvent::getSimilarMarketId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Map<String, Object>> metaById = ids.isEmpty()
                ? Map.of()
                : similarMarketsRepository.findMetadataByIds(ids).stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("id")).longValue(),
                        row -> row,
                        (a, _) -> a));

        return events.stream().map(e -> {
            Map<String, Object> m = metaById.getOrDefault(e.getSimilarMarketId(), Map.of());
            return new ActiveArbitrageEventDto(
                    e.getId(),
                    e.getUuid(),
                    e.getDetectedAt(),
                    e.getSimilarMarketId(),
                    e.getDirection(),
                    e.getSpread(),
                    e.getPolymarketYesAsk(),
                    e.getPolymarketNoAsk(),
                    e.getKalshiYesAsk(),
                    e.getKalshiNoAsk(),
                    e.getPolymarketMarketTicker(),
                    (String) m.get("polymarket_event_ticker"),
                    (String) m.get("polymarket_event_title"),
                    (String) m.get("polymarket_market_title"),
                    toOffsetDateTime(m.get("polymarket_market_close_datetime")),
                    e.getKalshiMarketTicker(),
                    (String) m.get("kalshi_event_ticker"),
                    (String) m.get("kalshi_event_title"),
                    (String) m.get("kalshi_market_title"),
                    toOffsetDateTime(m.get("kalshi_market_close_datetime"))
            );
        }).toList();
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        return switch (value) {
            case OffsetDateTime odt -> odt;
            case java.sql.Timestamp ts -> ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
            case java.time.Instant i -> i.atOffset(java.time.ZoneOffset.UTC);
            case null, default -> null;
        };
    }
}
