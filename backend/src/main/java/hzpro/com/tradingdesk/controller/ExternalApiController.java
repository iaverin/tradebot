package hzpro.com.tradingdesk.controller;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import hzpro.com.tradingdesk.similarmarkets.service.SimilarMarketsService;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/external")
@Slf4j
public class ExternalApiController {

    private final SimilarMarketsService similarMarketsService;

    public ExternalApiController(SimilarMarketsService similarMarketsService) {
        this.similarMarketsService = similarMarketsService;
    }

    @GetMapping("/similar-markets")
    public ResponseEntity<Page<Map<String, Object>>> listSimilarMarkets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size
    ) {
        return ResponseEntity.ok(similarMarketsService.getSimilarMarkets(page, size));
    }
}
