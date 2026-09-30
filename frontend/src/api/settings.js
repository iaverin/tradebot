import api from './axios'

export function getSettingsInfo() {
  return api.get('/api/settings/info')
}

export function getTradingEnabled() {
  return api.get('/api/settings/trading-enabled')
}

export function setTradingEnabled(tradingEnabled) {
  return api.put('/api/settings/trading-enabled', { tradingEnabled })
}

export function setActivePairsLimit(activePairsLimit) {
  return api.put('/api/settings/active-pairs-limit', { activePairsLimit })
}
