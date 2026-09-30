package hzpro.com.tradingdesk.controller;

import hzpro.com.tradingdesk.service.account.KalshiBalanceService;
import hzpro.com.tradingdesk.service.account.PolymarketBalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Read-only account balances for the trading venues. Mapped under {@code /api/**}, so every endpoint
 * requires a valid JWT (see {@code SecurityConfig}).
 */
@Slf4j
@RestController
@RequestMapping("/balance")
@RequiredArgsConstructor
public class BalanceController {

    private final KalshiBalanceService kalshiBalanceService;
    private final PolymarketBalanceService polymarketBalanceService;

    public record BalanceResponceDto(BigDecimal balanceDollars) {}

    @GetMapping("/kalshi")
    public BalanceResponceDto kalshiBalance() {
        try {
            return new BalanceResponceDto(
                kalshiBalanceService.getBalance().balanceDollars());
        } catch (Exception e) {
            throw new RuntimeException( e.getMessage());
        }
    }

    @GetMapping("/polymarket")
    public BalanceResponceDto polymarketBalance() {
        try {
            return new BalanceResponceDto(polymarketBalanceService.getBalance().balance());
        } catch (Exception e) {
            throw new RuntimeException( e.getMessage());
        }
    }

    public record ErrorResponseDto(String message, long timestamp) {
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponseDto> handleLocalException(RuntimeException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponseDto(ex.getMessage(), System.currentTimeMillis()));
    }

}
