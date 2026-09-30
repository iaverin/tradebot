import api from './axios'

export function proxyRequest(venue, method, path, body) {
  return api.post('/api/requests/proxy', {
    venue,
    method: method || 'GET',
    path,
    body: body || null
  })
}

export function getRequestsConfig() {
  return api.get('/api/requests/config')
}
