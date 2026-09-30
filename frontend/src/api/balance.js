
import api from './axios'

export const balanceApi = {
  balanceKalshi: () => api.get('/balance/kalshi'),
  balancePolymarket: () => api.get('/balance/polymarket'),
}
