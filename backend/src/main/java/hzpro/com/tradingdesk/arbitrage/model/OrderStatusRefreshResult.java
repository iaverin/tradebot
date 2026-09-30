package hzpro.com.tradingdesk.arbitrage.model;

public record OrderStatusRefreshResult(boolean completed, int checkedOrders, int updatedOrders) {

    public static OrderStatusRefreshResult failed() {
        return new OrderStatusRefreshResult(false, 0, 0);
    }
}
