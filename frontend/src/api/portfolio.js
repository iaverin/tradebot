import api from './axios'

export const portfolioApi = {
  getDashboard: () => api.get('/portfolio/dashboard'),
  getPositions: (venue, page = 0, size = 50) =>
    api.get('/portfolio/positions', { params: { venue, page, size } }),
  refreshPositions: () => api.post('/portfolio/positions/refresh'),
}
