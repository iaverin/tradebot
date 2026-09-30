export function orderStatusPath(platform, orderId) {
  if (platform === 'POLYMARKET') return `/data/order/${orderId}`
  if (platform === 'KALSHI') return `/portfolio/orders/${orderId}`
  return '#'
}

export function orderRequestsUrl(platform, orderId) {
  return `/requests?venue=${encodeURIComponent(platform)}&path=${encodeURIComponent(orderStatusPath(platform, orderId))}&method=GET`
}

export function fmtFull(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  const y = d.getFullYear()
  const M = String(d.getMonth() + 1).padStart(2, '0')
  const D = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const m = String(d.getMinutes()).padStart(2, '0')
  const s = String(d.getSeconds()).padStart(2, '0')
  return `${y}-${M}-${D} ${h}:${m}:${s}`
}

export function statusTagType(status) {
  switch (status) {
    case 'EXECUTED': return 'success'
    case 'PLACED': return 'warning'
    case 'CANCELED': return 'info'
    case 'ERROR': return 'danger'
    default: return ''
  }
}
