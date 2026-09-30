package hzpro.com.tradingdesk.marketsfetcher.dto.opinion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Envelope returned by the opinion.trade Open API.
 * Success is signalled by {@code errno == 0} (the docs call this {@code code}).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpinionResponseDto {
    private int errno;
    private String errmsg;
    private OpinionResultDto result;
}
