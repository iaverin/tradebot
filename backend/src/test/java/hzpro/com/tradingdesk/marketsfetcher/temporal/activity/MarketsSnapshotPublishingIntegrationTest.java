package hzpro.com.tradingdesk.marketsfetcher.temporal.activity;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.SimilarMarket;
import hzpro.com.tradingdesk.entity.SimilarMarketStaging;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import hzpro.com.tradingdesk.repository.SimilarMarketStagingRepository;
import hzpro.com.tradingdesk.similarmarkets.repository.SimilarMarketsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class MarketsSnapshotPublishingIntegrationTest {

    @Autowired
    private FetchActivityImpl activity;

    @MockitoSpyBean
    private PredictionMarketRepository predictionMarketRepository;

    @Autowired
    private SimilarMarketsRepository similarMarketsRepository;

    @Autowired
    private SimilarMarketStagingRepository similarMarketStagingRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            similarMarketStagingRepository.deleteAllInBatch();
            predictionMarketRepository.truncateFetchingTable();
            predictionMarketRepository.truncatePublishedTables();
        });
        insertProductionSentinels();
        insertStagingSnapshot();
    }

    @Test
    void atomicallyPublishesBothTablesAndUsesNewSimilarMarketIds() {
        Long oldSimilarMarketId = findSimilarMarketId("old-pm-event");

        activity.publishMarketsSnapshot();

        assertThat(predictionMarketRepository.countPublishedByDatasource(DataSource.KALSHI.name())).isZero();
        assertThat(predictionMarketRepository.countPublishedByDatasource(DataSource.POLYMARKET.name())).isOne();
        assertThat(similarMarketsRepository.countByPolymarketEventTicker("old-pm-event")).isZero();
        assertThat(similarMarketsRepository.countByPolymarketEventTicker("new-pm-event")).isOne();

        Long newSimilarMarketId = findSimilarMarketId("new-pm-event");
        assertThat(newSimilarMarketId).isGreaterThan(oldSimilarMarketId);
    }

    @Test
    void rollsBackBothProductionTablesWhenSecondCopyFails() {
        doThrow(new RuntimeException("forced publication failure"))
                .when(predictionMarketRepository).copyStagingToSimilarMarkets();

        assertThatThrownBy(activity::publishMarketsSnapshot).isInstanceOf(RuntimeException.class);

        assertThat(predictionMarketRepository.countPublishedByDatasource(DataSource.KALSHI.name())).isOne();
        assertThat(predictionMarketRepository.countPublishedByDatasource(DataSource.POLYMARKET.name())).isZero();
        assertThat(similarMarketsRepository.countByPolymarketEventTicker("old-pm-event")).isOne();
        assertThat(similarMarketsRepository.countByPolymarketEventTicker("new-pm-event")).isZero();
    }

    private Long findSimilarMarketId(String polymarketEventTicker) {
        return similarMarketsRepository
                .findFirstByPolymarketEventTickerOrderByIdDesc(polymarketEventTicker)
                .orElseThrow()
                .getId();
    }

    private void insertProductionSentinels() {
        predictionMarketRepository.saveAndFlush(PredictionMarket.builder()
                .datasource(DataSource.KALSHI)
                .build());
        transactionTemplate.executeWithoutResult(status -> predictionMarketRepository.copyFetchingToPredictionMarkets());
        predictionMarketRepository.deleteAll();

        Instant now = Instant.now();
        similarMarketsRepository.save(SimilarMarket.builder()
                .createdAt(now)
                .updatedAt(now)
                .similarity(0.9)
                .polymarketEventTicker("old-pm-event")
                .polymarketEventTitle("Old PM event")
                .polymarketMarketTicker("old-pm-market")
                .polymarketMarketTitle("Old PM market")
                .kalshiEventTicker("old-ks-event")
                .kalshiEventTitle("Old KS event")
                .kalshiMarketTicker("old-ks-market")
                .kalshiMarketTitle("Old KS market")
                .build());
    }

    private void insertStagingSnapshot() {
        predictionMarketRepository.save(PredictionMarket.builder()
                .datasource(DataSource.POLYMARKET)
                .eventTicker("new-pm-event")
                .marketTicker("new-pm-market")
                .yesTokenId("yes-token")
                .noTokenId("no-token")
                .conditionId("condition")
                .build());

        Instant now = Instant.now();
        similarMarketStagingRepository.save(SimilarMarketStaging.builder()
                .createdAt(now)
                .updatedAt(now)
                .similarity(0.95)
                .polymarketEventTicker("new-pm-event")
                .polymarketEventTitle("New PM event")
                .polymarketMarketTicker("new-pm-market")
                .polymarketMarketTitle("New PM market")
                .kalshiEventTicker("new-ks-event")
                .kalshiEventTitle("New KS event")
                .kalshiMarketTicker("new-ks-market")
                .kalshiMarketTitle("New KS market")
                .build());
    }
}
