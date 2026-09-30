package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.controller.dto.PortfolioPositionDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioPositionGroupDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioPositionRelationDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioPositionsPageDto;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.portfolio.entity.PortfolioPosition;
import hzpro.com.tradingdesk.portfolio.model.PortfolioPositionKey;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioPositionRefreshStateRepository;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioPositionRepository;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioRelationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PortfolioPositionQueryService {

    private static final Comparator<PortfolioPosition> RELATED_ORDER = Comparator
            .comparing(PortfolioPosition::getEventTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(PortfolioPosition::getMarketTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(PortfolioPosition::getOutcome)
            .thenComparing(PortfolioPosition::getId);

    private final PortfolioPositionRepository positionRepository;
    private final PortfolioPositionRefreshStateRepository refreshStateRepository;
    private final PortfolioRelationRepository relationRepository;

    public PortfolioPositionQueryService(
            PortfolioPositionRepository positionRepository,
            PortfolioPositionRefreshStateRepository refreshStateRepository,
            PortfolioRelationRepository relationRepository
    ) {
        this.positionRepository = positionRepository;
        this.refreshStateRepository = refreshStateRepository;
        this.relationRepository = relationRepository;
    }

    @Transactional(readOnly = true)
    public PortfolioPositionsPageDto getPositions(String venueValue, int pageNumber, int pageSize) {
        PortfolioVenue venue = PortfolioVenue.parse(venueValue);
        if (pageNumber < 0) {
            throw new IllegalArgumentException("page must be zero or greater");
        }
        if (pageSize < 1 || pageSize > 200) {
            throw new IllegalArgumentException("size must be between 1 and 200");
        }

        Sort sort = Sort.by(
                Sort.Order.asc("eventTitle"),
                Sort.Order.asc("marketTitle"),
                Sort.Order.asc("outcome"),
                Sort.Order.asc("id"));
        Page<PortfolioPosition> page = positionRepository.findByVenue(
                DataSource.valueOf(venue.name()),
                PageRequest.of(pageNumber, pageSize, sort));

        List<String> primaryTickers = page.getContent().stream()
                .map(PortfolioPosition::getMarketTicker)
                .distinct()
                .toList();
        Map<PortfolioPositionKey, Set<PortfolioPositionKey>> relations = buildRelationIndex(
                relationRepository.findRelations(venue, primaryTickers));
        Set<PortfolioPositionKey> counterpartKeys = new LinkedHashSet<>();
        for (PortfolioPosition primary : page.getContent()) {
            counterpartKeys.addAll(relations.getOrDefault(key(primary), Set.of()));
        }
        List<String> counterpartTickers = counterpartKeys.stream()
                .map(PortfolioPositionKey::marketTicker)
                .distinct()
                .toList();
        Map<PortfolioPositionKey, PortfolioPosition> otherVenuePositions = new LinkedHashMap<>();
        if (!counterpartTickers.isEmpty()) {
            for (PortfolioPosition position : positionRepository.findByVenueAndMarketTickerIn(
                    DataSource.valueOf(venue.other().name()),
                    counterpartTickers)) {
                otherVenuePositions.put(key(position), position);
            }
        }

        List<PortfolioPositionGroupDto> content = new ArrayList<>();
        for (PortfolioPosition primary : page.getContent()) {
            List<PortfolioPosition> related = relations.getOrDefault(key(primary), Set.of()).stream()
                    .map(otherVenuePositions::get)
                    .filter(java.util.Objects::nonNull)
                    .sorted(RELATED_ORDER)
                    .toList();
            content.add(new PortfolioPositionGroupDto(
                    primary.getId(),
                    toDto(primary),
                    related.stream().map(this::toDto).toList()));
        }

        return new PortfolioPositionsPageDto(
                venue.name(),
                refreshStateRepository.findById(venue.name())
                        .map(state -> state.getLastSuccessfulRefreshAt())
                        .orElse(null),
                content,
                pageNumber,
                pageSize,
                page.getTotalElements(),
                page.getTotalPages());
    }

    private Map<PortfolioPositionKey, Set<PortfolioPositionKey>> buildRelationIndex(
            List<PortfolioPositionRelationDto> relations
    ) {
        Map<PortfolioPositionKey, Set<PortfolioPositionKey>> index = new LinkedHashMap<>();
        for (PortfolioPositionRelationDto relation : relations) {
            PortfolioPositionKey polymarket = new PortfolioPositionKey(
                    "POLYMARKET",
                    relation.polymarketMarketTicker(),
                    relation.polymarketOutcome());
            PortfolioPositionKey kalshi = new PortfolioPositionKey(
                    "KALSHI",
                    relation.kalshiMarketTicker(),
                    relation.kalshiOutcome());
            index.computeIfAbsent(polymarket, ignored -> new LinkedHashSet<>()).add(kalshi);
            index.computeIfAbsent(kalshi, ignored -> new LinkedHashSet<>()).add(polymarket);
        }
        return index;
    }

    private PortfolioPositionKey key(PortfolioPosition position) {
        return new PortfolioPositionKey(
                position.getVenue().name(),
                position.getMarketTicker(),
                position.getOutcome().name());
    }

    private PortfolioPositionDto toDto(PortfolioPosition position) {
        return new PortfolioPositionDto(
                position.getId(),
                position.getVenue().name(),
                position.getEventTicker(),
                position.getEventTitle(),
                position.getMarketTicker(),
                position.getMarketTitle(),
                position.getOutcome().name(),
                position.getQuantity(),
                position.getSpentUsd(),
                position.getCurrentValueUsd(),
                position.getRefreshedAt());
    }
}
