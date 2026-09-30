package hzpro.com.tradingdesk.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PolymarketPositionDto(
        String asset,
        String conditionId,
        BigDecimal size,
        BigDecimal avgPrice,
        BigDecimal initialValue,
        BigDecimal grossInitialValue,
        BigDecimal currentValue,
        String title,
        String slug,
        String eventSlug,
        String outcome
) {
}
