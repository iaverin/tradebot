package hzpro.com.tradingdesk.marketsfetcher.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface FetchActivity {
    @ActivityMethod
    void clearFetchingData();

    @ActivityMethod
    void fetchKalshi();

    @ActivityMethod
    void fetchPolymarket();

    @ActivityMethod
    void fetchOpinion();

    @ActivityMethod
    void publishMarketsSnapshot();

    @ActivityMethod
    void stopArbitrageMonitor();

    @ActivityMethod
    void startArbitrageMonitor();
}
