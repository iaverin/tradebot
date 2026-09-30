package hzpro.com.tradingdesk.arbitrage.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kalshi orderbook response wrapper.
 *
 * <p>The Kalshi REST endpoint {@code GET /markets/{ticker}/orderbook} returns only bids
 * (no asks): {@code yes_dollars} are YES bids, {@code no_dollars} are NO bids. Each entry
 * is a 2-element array {@code [price_dollars, count_fp]} — both as strings to allow
 * sub-penny pricing and fixed-point sizes.
 *
 * <p>See <a href="https://docs.kalshi.com/getting_started/orderbook_responses">Kalshi
 * orderbook responses</a> for the full reciprocal-pricing semantics (a YES bid at $X is
 * equivalent to a NO ask at $1.00 - X, and vice versa).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KalshiOrderBookDto(
        @JsonProperty("orderbook_fp") OrderBookFp orderbookFp
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderBookFp(
            @JsonProperty("yes_dollars") List<List<String>> yesDollars,
            @JsonProperty("no_dollars") List<List<String>> noDollars
    ) {
    }
}
