package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.portfolio.entity.PortfolioPosition;
import hzpro.com.tradingdesk.portfolio.entity.PortfolioPositionRefreshState;
import hzpro.com.tradingdesk.portfolio.model.CollectedPortfolioPosition;
import hzpro.com.tradingdesk.portfolio.model.PortfolioPositionKey;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioPositionRefreshStateRepository;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioPositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PortfolioPositionSnapshotWriter {

    private final PortfolioPositionRepository positionRepository;
    private final PortfolioPositionRefreshStateRepository refreshStateRepository;

    public PortfolioPositionSnapshotWriter(
            PortfolioPositionRepository positionRepository,
            PortfolioPositionRefreshStateRepository refreshStateRepository
    ) {
        this.positionRepository = positionRepository;
        this.refreshStateRepository = refreshStateRepository;
    }

    @Transactional
    public Instant replaceSnapshot(
            PortfolioVenue venue,
            List<CollectedPortfolioPosition> collectedPositions,
            Instant refreshedAt
    ) {
        Map<PortfolioPositionKey, CollectedPortfolioPosition> incoming = validateAndDeduplicate(
                venue,
                collectedPositions);
        Map<PortfolioPositionKey, PortfolioPosition> existing = new LinkedHashMap<>();
        for (PortfolioPosition position : positionRepository.findByVenue(DataSource.valueOf(venue.name()))) {
            existing.put(key(position), position);
        }

        List<PortfolioPosition> retained = new ArrayList<>();
        for (Map.Entry<PortfolioPositionKey, CollectedPortfolioPosition> entry : incoming.entrySet()) {
            PortfolioPosition position = existing.remove(entry.getKey());
            if (position == null) {
                position = new PortfolioPosition();
                position.setVenue(DataSource.valueOf(venue.name()));
            }
            copy(entry.getValue(), position, refreshedAt);
            retained.add(position);
        }

        positionRepository.saveAll(retained);
        positionRepository.deleteAll(existing.values());

        PortfolioPositionRefreshState state = refreshStateRepository.findById(venue.name())
                .orElseGet(PortfolioPositionRefreshState::new);
        state.setVenue(venue.name());
        state.setLastSuccessfulRefreshAt(refreshedAt);
        state.setUpdatedAt(refreshedAt);
        refreshStateRepository.save(state);
        return refreshedAt;
    }

    private Map<PortfolioPositionKey, CollectedPortfolioPosition> validateAndDeduplicate(
            PortfolioVenue venue,
            List<CollectedPortfolioPosition> positions
    ) {
        if (positions == null) {
            throw new IllegalArgumentException("A successful snapshot cannot be null");
        }
        Map<String, CollectedPortfolioPosition> bySourceId = new LinkedHashMap<>();
        for (CollectedPortfolioPosition raw : positions) {
            CollectedPortfolioPosition position = normalize(raw);
            validate(position);
            CollectedPortfolioPosition previous = bySourceId.putIfAbsent(position.sourcePositionId(), position);
            if (previous != null && !previous.equals(position)) {
                throw new IllegalArgumentException(
                        "Conflicting rows for source position " + position.sourcePositionId());
            }
        }

        Map<PortfolioPositionKey, CollectedPortfolioPosition> byRelation = new LinkedHashMap<>();
        for (CollectedPortfolioPosition position : bySourceId.values()) {
            PortfolioPositionKey key = new PortfolioPositionKey(
                    venue.name(),
                    position.marketTicker(),
                    position.outcome());
            CollectedPortfolioPosition previous = byRelation.putIfAbsent(key, position);
            if (previous != null && !previous.sourcePositionId().equals(position.sourcePositionId())) {
                throw new IllegalArgumentException("Multiple source positions collide on " + key);
            }
        }
        return byRelation;
    }

    private CollectedPortfolioPosition normalize(CollectedPortfolioPosition position) {
        if (position == null) {
            throw new IllegalArgumentException("Snapshot contains a null position");
        }
        return new CollectedPortfolioPosition(
                trim(position.sourcePositionId()),
                trim(position.marketTicker()),
                trimToNull(position.conditionId()),
                trimToNull(position.eventTicker()),
                trimToNull(position.eventTitle()),
                trimToNull(position.marketTitle()),
                position.outcome() == null ? null : position.outcome().trim().toUpperCase(Locale.ROOT),
                position.quantity(),
                position.spentUsd(),
                position.currentValueUsd(),
                position.sourceUpdatedAt());
    }

    private void validate(CollectedPortfolioPosition position) {
        if (isBlank(position.sourcePositionId()) || isBlank(position.marketTicker())
                || isBlank(position.outcome())) {
            throw new IllegalArgumentException("Position is missing a required identity field");
        }
        if (!"YES".equals(position.outcome()) && !"NO".equals(position.outcome())) {
            throw new IllegalArgumentException("Position outcome must be YES or NO");
        }
        if (position.quantity() == null || position.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Position quantity must be positive");
        }
    }

    private void copy(
            CollectedPortfolioPosition source,
            PortfolioPosition target,
            Instant refreshedAt
    ) {
        target.setSourcePositionId(source.sourcePositionId());
        target.setMarketTicker(source.marketTicker());
        target.setConditionId(source.conditionId());
        target.setEventTicker(source.eventTicker());
        target.setEventTitle(source.eventTitle());
        target.setMarketTitle(source.marketTitle());
        target.setOutcome(YesOrNoResult.valueOf(source.outcome()));
        target.setQuantity(source.quantity());
        target.setSpentUsd(source.spentUsd());
        target.setCurrentValueUsd(source.currentValueUsd());
        target.setSourceUpdatedAt(source.sourceUpdatedAt());
        target.setRefreshedAt(refreshedAt);
    }

    private PortfolioPositionKey key(PortfolioPosition position) {
        return new PortfolioPositionKey(
                position.getVenue().name(),
                position.getMarketTicker(),
                position.getOutcome().name());
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String trimToNull(String value) {
        String trimmed = trim(value);
        return isBlank(trimmed) ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
