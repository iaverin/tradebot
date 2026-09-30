package hzpro.com.tradingdesk.marketsfetcher.service.fetchers;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.marketsfetcher.lib.FetcherState;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual, network-hitting smoke test: fetches ONE batch (page) from the live
 * opinion.trade Open API using the real {@code OPINION_API_KEY} from {@code .env}.
 *
 * <p>Excluded from the normal {@code ./gradlew test} run (tagged {@code manual}).
 * Run on demand with:
 * <pre>./gradlew manualTest --tests "*OpinionFetcherManualTest"</pre>
 */
@Tag("manual")
@SpringBootTest
@ActiveProfiles("test")
class OpinionFetcherManualTest {

    @Autowired
    private OpinionFetcher opinionFetcher;

    @Autowired
    private PredictionMarketRepository repository;

    @Test
    void fetchOneBatchFromLiveApi() {
        repository.deleteAll();

        // fetch() performs exactly one HTTP request (one page, limit=20) and persists it.
        FetcherState result = opinionFetcher.fetchAndPersist(opinionFetcher.initialState());

        System.out.println("\n================ opinion.trade one-batch fetch ================");
        System.out.println("URL:            " + result.getUrl());
        System.out.println("Query params:   " + result.getRequestQueryParameters());
        System.out.println("Fetch status:   " + result.getFetchStatus());
        System.out.println("Error message:  " + result.getErrorMessage());
        System.out.println("Markets fetched:" + result.getMarketsFetched());
        System.out.println("Has more data:  " + result.isHasMoreData());

        assertThat(result.getFetchStatus())
                .as("live fetch should succeed (check OPINION_API_KEY in .env)")
                .isEqualTo(FetcherState.FetchStatus.SUCCESS);

        List<PredictionMarket> saved = repository.findByDatasource(DataSource.OPINION);
        System.out.println("Rows persisted: " + saved.size());
        saved.stream().limit(10).forEach(m -> System.out.printf(
                "  event=%s ticker=%s%n    title=%s%n    open=%s close=%s status=%s%n    yes=%s no=%s cond=%s%n",
                m.getEventId(), m.getMarketTicker(), m.getMarketTitle(),
                m.getMarketOpenDatetime(), m.getMarketCloseDatetime(), m.getMarketStatus(),
                truncate(m.getYesTokenId()), truncate(m.getNoTokenId()), m.getConditionId()));
        System.out.println("===============================================================\n");

        assertThat(saved).isNotEmpty();
        assertThat(saved).allMatch(m -> m.getEventId() != null
                && m.getMarketTicker() != null
                && m.getMarketTitle() != null);

        // Tidy up so the manual run leaves no OPINION rows behind.
        repository.deleteAll(saved);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 16 ? s.substring(0, 16) + "…" : s;
    }
}
