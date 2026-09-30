package hzpro.com.tradingdesk.controller;

import hzpro.com.tradingdesk.controller.dto.PortfolioDashboardDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioErrorResponseDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioPositionsPageDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioRefreshResponseDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioVenueDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioVenueRefreshResultDto;
import hzpro.com.tradingdesk.portfolio.model.PortfolioRefreshResult;
import hzpro.com.tradingdesk.portfolio.service.PortfolioPositionQueryService;
import hzpro.com.tradingdesk.portfolio.service.PortfolioPositionRefreshCoordinator;
import hzpro.com.tradingdesk.portfolio.service.PortfolioRefreshInProgressException;
import hzpro.com.tradingdesk.service.account.KalshiPortfolioService;
import hzpro.com.tradingdesk.service.account.PolymarketPortfolioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;
import java.time.Instant;

/**
 * Portfolio dashboard endpoint providing six portfolio indicators per venue.
 * Mapped under {@code /portfolio} (same pattern as {@link BalanceController}).
 */
@Slf4j
@RestController
@RequestMapping("/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final KalshiPortfolioService kalshiPortfolioService;
    private final PolymarketPortfolioService polymarketPortfolioService;
    private final PortfolioPositionQueryService portfolioPositionQueryService;
    private final PortfolioPositionRefreshCoordinator portfolioPositionRefreshCoordinator;

    @GetMapping("/dashboard")
    public PortfolioDashboardDto dashboard() {
        PortfolioVenueDto kalshi = fetchSafely(kalshiPortfolioService::getPortfolio, "Kalshi");
        PortfolioVenueDto polymarket = fetchSafely(polymarketPortfolioService::getPortfolio, "Polymarket");
        return new PortfolioDashboardDto(kalshi, polymarket);
    }

    @GetMapping("/positions")
    public PortfolioPositionsPageDto positions(
            @RequestParam(defaultValue = "POLYMARKET") String venue,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return portfolioPositionQueryService.getPositions(venue, page, size);
    }

    @PostMapping("/positions/refresh")
    public PortfolioRefreshResponseDto refreshPositions() {
        PortfolioRefreshResult result = portfolioPositionRefreshCoordinator.refreshAll();
        return new PortfolioRefreshResponseDto(
                result.requestedAt(),
                result.allSucceeded(),
                result.venues().stream()
                        .map(venue -> new PortfolioVenueRefreshResultDto(
                                venue.venue().name(),
                                venue.status().name(),
                                venue.lastSuccessfulRefreshAt(),
                                venue.message()))
                        .toList());
    }

    private PortfolioVenueDto fetchSafely(Supplier<PortfolioVenueDto> supplier, String venue) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Failed to fetch portfolio for {}: {}", venue, e.getMessage());
            return new PortfolioVenueDto(null, null, null, null, null, null);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PortfolioErrorResponseDto> handleLocalException(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new PortfolioErrorResponseDto(ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(PortfolioRefreshInProgressException.class)
    public ResponseEntity<PortfolioErrorResponseDto> handleRefreshInProgress(
            PortfolioRefreshInProgressException ex
    ) {
        return ResponseEntity.status(409)
                .body(new PortfolioErrorResponseDto(ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<PortfolioErrorResponseDto> handleParameterTypeMismatch(
            MethodArgumentTypeMismatchException ex
    ) {
        return ResponseEntity.badRequest()
                .body(new PortfolioErrorResponseDto(
                        "Invalid parameter: " + ex.getName(),
                        Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<PortfolioErrorResponseDto> handleUnexpectedException(Exception ex) {
        log.error("Portfolio request failed", ex);
        return ResponseEntity.internalServerError()
                .body(new PortfolioErrorResponseDto("Portfolio request failed", Instant.now()));
    }
}
