import api from './axios'

export const arbitrageApi = {
    getStatus: () => api.get('/api/arbitrage/status'),
    restart: () => api.post('/api/arbitrage/restart'),
    getActiveEvents: () => api.get('/api/arbitrage/events/active'),
    getOrders: (uuid) => api.get(`/api/arbitrage/orders/${uuid}`),
    getOrdersStats: () => api.get('/api/arbitrage/orders/stats'),
    getRecentOrders: (limit = 50) => api.get('/api/arbitrage/orders/recent', { params: { limit } }),
    getDailyOrderTotals: (startDate, endDate) => api.get('/api/arbitrage/orders/daily-totals', {
        params: { startDate, endDate }
    }),
    getDailyOpportunityStats: (startDate, endDate) => api.get('/api/arbitrage/opportunities/daily-stats', {
        params: { startDate, endDate }
    }),
    getOrdersPage: (page = 0, size = 50, status = null, platform = null) => {
        const params = { page, size };
        if (status) params.status = status;
        if (platform) params.platform = platform;
        return api.get('/api/arbitrage/orders', { params });
    },
    getOpportunityReport: (startDate, endDate, onlyWithOrders = true) => {
        const params = { onlyWithOrders };
        if (startDate) params.startDate = startDate;
        if (endDate) params.endDate = endDate;
        return api.get('/api/arbitrage/opportunities/report', { params });
    },
    getProfitReport: (closeSource, startDate, endDate) => {
        const params = { closeSource };
        if (startDate) params.startDate = startDate;
        if (endDate) params.endDate = endDate;
        return api.get('/api/arbitrage/opportunities/profit-report', { params });
    },
}
