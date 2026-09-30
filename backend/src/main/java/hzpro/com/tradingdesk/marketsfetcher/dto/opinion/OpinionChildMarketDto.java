package hzpro.com.tradingdesk.marketsfetcher.dto.opinion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * A single market belonging to a categorical (multi-outcome) opinion.trade event.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpinionChildMarketDto {
    private long marketId;
    private String marketTitle;
    private int status;
    private String statusEnum;

    private String yesLabel;
    private String noLabel;
    private String rules;
    private String yesTokenId;
    private String noTokenId;
    private String conditionId;
    private String resultTokenId;
    private String volume;
    private String quoteToken;
    private String chainId;
    private String questionId;
    private String slug;

    /** Epoch seconds. */
    private Long createdAt;
    private Long cutoffAt;
    private Long resolvedAt;
}
