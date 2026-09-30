package hzpro.com.tradingdesk.portfolio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import hzpro.com.tradingdesk.client.dto.KalshiMarketPositionDto;
import hzpro.com.tradingdesk.client.dto.KalshiMarketQuoteDto;
import hzpro.com.tradingdesk.client.dto.KalshiMarketsPageDto;
import hzpro.com.tradingdesk.client.dto.KalshiPortfolioPositionsPageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class KalshiPortfolioPositionClient {

    private static final int POSITIONS_LIMIT = 1_000;
    private static final int MARKET_BATCH_SIZE = 100;

    private final KalshiAuthorizedClient client;
    private final ObjectMapper objectMapper;

    public KalshiPortfolioPositionClient(KalshiAuthorizedClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public List<KalshiMarketPositionDto> getCurrentPositions() {
        List<KalshiMarketPositionDto> positions = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;

        do {
            String path = "/portfolio/positions?count_filter=position&limit=" + POSITIONS_LIMIT;
            if (cursor != null && !cursor.isBlank()) {
                path += "&cursor=" + encode(cursor);
            }
            KalshiPortfolioPositionsPageDto page = client.execute(
                    HttpMethod.GET,
                    path,
                    response -> {
                        if (response.getCode() != 200) {
                            log.warn("Kalshi portfolio positions returned {}", response.getCode());
                            return null;
                        }
                        return objectMapper.readValue(
                                response.getEntity().getContent(),
                                KalshiPortfolioPositionsPageDto.class);
                    });
            if (page == null) {
                return null;
            }
            positions.addAll(page.marketPositions());
            cursor = page.cursor();
            if (cursor != null && !cursor.isBlank() && !seenCursors.add(cursor)) {
                log.warn("Kalshi portfolio positions returned a repeated cursor");
                return null;
            }
        } while (cursor != null && !cursor.isBlank());

        return positions;
    }

    /**
     * Returns market metadata indexed by ticker. A failed batch is logged and omitted so positions
     * can still be stored with a nullable current value.
     */
    public Map<String, KalshiMarketQuoteDto> getMarkets(List<String> tickers) {
        Map<String, KalshiMarketQuoteDto> markets = new LinkedHashMap<>();
        for (int start = 0; start < tickers.size(); start += MARKET_BATCH_SIZE) {
            List<String> batch = tickers.subList(start, Math.min(start + MARKET_BATCH_SIZE, tickers.size()));
            String joinedTickers = batch.stream().map(this::encode).reduce((left, right) -> left + "," + right)
                    .orElse("");
            String path = "/markets?tickers=" + joinedTickers + "&limit=1000";
            KalshiMarketsPageDto page = client.execute(
                    HttpMethod.GET,
                    path,
                    response -> {
                        if (response.getCode() != 200) {
                            log.warn("Kalshi market metadata returned {}", response.getCode());
                            return null;
                        }
                        return objectMapper.readValue(response.getEntity().getContent(), KalshiMarketsPageDto.class);
                    });
            if (page == null) {
                log.warn("Kalshi market metadata batch failed for {} ticker(s)", batch.size());
                continue;
            }
            for (KalshiMarketQuoteDto market : page.markets()) {
                if (market.ticker() != null) {
                    markets.put(market.ticker(), market);
                }
            }
        }
        return markets;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
