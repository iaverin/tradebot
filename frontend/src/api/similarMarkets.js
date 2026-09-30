import api from './axios'

export const similarMarketsApi = {
  countSimilarMarkets: () => api.get('/similar-markets/count-similar-markets'),
  countFetchedMarkets: () => api.get('/similar-markets/count-fetched-markets'),
  countAllowedMarketPairs: () => api.get('/similar-markets/count-allowed-market-pairs'),
  recalculate: () => api.post('/similar-markets/recalculate-similar-markets'),
  list: (page = 0, size = 100) => api.get('/similar-markets/list', { params: { page, size } }),
  toggleEnabled: (id) => api.put(`/similar-markets/${id}/toggle`),
  toggleEventEnabled: (polymarketEventTicker, kalshiEventTicker, enabled) =>
    api.put('/similar-markets/event/toggle', { polymarketEventTicker, kalshiEventTicker, enabled }),
}
