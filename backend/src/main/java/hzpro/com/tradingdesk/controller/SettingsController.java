package hzpro.com.tradingdesk.controller;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.converter.HttpMessageNotReadableException;

import hzpro.com.tradingdesk.config.SettingsService;
import hzpro.com.tradingdesk.controller.dto.ActivePairsLimitDto;
import hzpro.com.tradingdesk.controller.dto.SettingsErrorResponseDto;
import hzpro.com.tradingdesk.controller.dto.SettingsInfoDto;
import hzpro.com.tradingdesk.controller.dto.TradingEnabledDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping("/info")
    public ResponseEntity<SettingsInfoDto> getSettingsInfo() {
        return ResponseEntity.ok(settingsService.getSettingsInfo());
    }

    @GetMapping("/trading-enabled")
    public ResponseEntity<TradingEnabledDto> getTradingEnabled() {
        return ResponseEntity.ok(new TradingEnabledDto(settingsService.getTradingEnabled()));
    }

    @PutMapping("/trading-enabled")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<TradingEnabledDto> setTradingEnabled(@RequestBody TradingEnabledDto dto) {
        boolean enabled = settingsService.setTradingEnabled(dto.tradingEnabled());

        if (enabled) {
            log.info("Will reset errors count");
            settingsService.resetErrorsCount();
        }
        return ResponseEntity.ok(new TradingEnabledDto(enabled));
    }

    @PutMapping("/active-pairs-limit")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<ActivePairsLimitDto> setActivePairsLimit(
            @Valid @RequestBody ActivePairsLimitDto dto) {
        int saved = settingsService.setActivePairsLimit(dto.activePairsLimit());
        return ResponseEntity.ok(new ActivePairsLimitDto(saved));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<SettingsErrorResponseDto> handleInvalidSetting(Exception exception) {
        String message = exception instanceof IllegalArgumentException
                ? exception.getMessage()
                : "Invalid settings request";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new SettingsErrorResponseDto(message, Instant.now()));
    }
}
