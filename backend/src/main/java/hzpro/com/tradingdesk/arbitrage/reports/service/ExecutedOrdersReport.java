package hzpro.com.tradingdesk.arbitrage.reports.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class ExecutedOrdersReport {

    private final ArbitrageOrderRepository orderRepository;


    public Map<LocalDate, BigDecimal>  getExecutedOrdersByDate(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate must not be after endDate");
        }

        return orderRepository
                .findByCreatedAtGreaterThanEqualAndCreatedAtLessThanAndStatusIn(
                        startDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                            List.of(OrderState.EXECUTED.name()))
                .stream()
                .collect(Collectors.groupingBy(
                        order -> order.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                order -> order.getPrice().multiply(BigDecimal.valueOf(order.getQuantity())),
                                BigDecimal::add)));

    }
}
