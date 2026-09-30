package hzpro.com.tradingdesk.arbitrage.reports.service;

import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOpportunityStatsRepository;
import hzpro.com.tradingdesk.controller.dto.DailyOpportunityStatsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DailyOpportunityStatsReport {

    private final ArbitrageOpportunityStatsRepository statsRepository;

    public List<DailyOpportunityStatsDto> getDailyStats(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate must not be after endDate");
        }

        Map<LocalDate, DailyOpportunityStatsDto> statsByDate = statsRepository.findDailyStats(
                        startDate.atStartOfDay().atOffset(ZoneOffset.UTC),
                        endDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
                .stream()
                .collect(Collectors.toMap(DailyOpportunityStatsDto::date, Function.identity()));

        return startDate.datesUntil(endDate.plusDays(1))
                .map(date -> statsByDate.getOrDefault(
                        date,
                        new DailyOpportunityStatsDto(date, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO)))
                .toList();
    }
}
