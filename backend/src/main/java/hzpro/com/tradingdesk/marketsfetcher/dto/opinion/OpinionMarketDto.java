package hzpro.com.tradingdesk.marketsfetcher.dto.opinion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level market (the "event"). For {@code marketType == 1} (Categorical) the
 * individual markets live in {@link #childMarkets}; for {@code marketType == 0}
 * (Binary) the top-level market is itself the single market row.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpinionMarketDto {
    private long marketId;
    private String marketTitle;
    private int status;
    private String statusEnum;
    private int marketType;

    private String yesLabel;
    private String noLabel;
    private String rules;
    private String yesTokenId;
    private String noTokenId;
    private String conditionId;
    private String resultTokenId;
    private String volume;
    private String volume24h;
    private String volume7d;
    private String quoteToken;
    private String chainId;
    private String questionId;
    private String slug;

    /** Epoch seconds. */
    private Long createdAt;
    private Long cutoffAt;
    private Long resolvedAt;

    private List<String> labels = new ArrayList<>();
    private List<Long> labelIds = new ArrayList<>();

    private List<OpinionChildMarketDto> childMarkets = new ArrayList<>();
}
