import api from './axios'

export const fetcherApi = {
  getStatus: () => api.get('/markets-fetcher/status'),
  getNextCronTime: () => api.get('/markets-fetcher/next-cron-time'),
  triggerPolymarket: () => api.post('/markets-fetcher/fetch-polymarket'),
  triggerKalshi: () => api.post('/markets-fetcher/fetch-kalshi'),
  triggerOpinion: () => api.post('/markets-fetcher/fetch-opinion'),
  triggerMarketsRefresh: () => api.post('/markets-fetcher/run-markets-refresh'),
  clearFetchingData: () => api.post('/markets-fetcher/clear-fetching-data'),
  pause: () => api.post('/markets-fetcher/pause'),
  resume: () => api.post('/markets-fetcher/resume'),
  stop: () => api.post('/markets-fetcher/stop'),
}
