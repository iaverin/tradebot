package hzpro.com.tradingdesk.marketsfetcher.lib;

import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;
import java.util.Map;

@Value
@Builder(toBuilder = true)
public class FetcherState {
    ZonedDateTime requestStartDatetime;
    ZonedDateTime requestEndDatetime;
    String url;
    Map<String, Object> requestQueryParameters;
    String responseText;
    FetchStatus fetchStatus;
    String errorMessage;
    boolean hasMoreData;
    Long requestNumber;
    Long marketsFetched;

    public enum FetchStatus {
        SUCCESS,
        ERROR,
        PENDING,
        IN_PROGRESS,
        PAUSE,
    }

    public static FetcherState initial(String baseUrl) {
        return FetcherState.builder()
                .url(baseUrl)
                .fetchStatus(FetchStatus.PENDING)
                .hasMoreData(true)
                .requestStartDatetime(ZonedDateTime.now())
                .requestNumber(0L)
                .marketsFetched(0L)
                .build();
    }
}
