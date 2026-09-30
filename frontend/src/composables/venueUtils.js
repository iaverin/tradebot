export function polymarketEventUrl(ticker) {
  if (!ticker) return '#'
  return `https://polymarket.com/event/${encodeURIComponent(ticker)}`
}

export function kalshiEventUrl(ticker) {
  if (!ticker) return '#'
  const series = ticker.split('-')[0]
  return `https://kalshi.com/markets/${encodeURIComponent(series)}/${encodeURIComponent(ticker)}`
}

export function venueEventUrl(platform, ticker) {
  if (!ticker) return '#'
  if (platform === 'POLYMARKET') return polymarketEventUrl(ticker)
  if (platform === 'KALSHI') return kalshiEventUrl(ticker)
  return '#'
}
