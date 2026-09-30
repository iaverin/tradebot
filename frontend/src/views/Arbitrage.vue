<template>
  <div class="arbitrage-container">
    <el-container>
      <SidebarMenu active-index="4" />

      <el-container>
        <el-header class="header">
          <div class="header-left">
            <h2>Arbitrage Opportunities</h2>
          </div>
          <div class="header-right">
            <el-dropdown @command="handleCommand">
              <span class="user-dropdown">
                <el-icon><User /></el-icon>
                {{ authStore.username }}
                <el-icon><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="logout">Logout</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </el-header>

        <el-main class="main-content">
          <el-row :gutter="20" style="margin-bottom: 20px;">
            <el-col :span="24">
              <el-card>
                <template #header>
                  <div class="card-header-row">
                    <span>Monitor Status</span>
                    <el-tag :type="monitorStatus.running ? 'success' : 'danger'" size="small">
                      {{ monitorStatus.running ? 'Running' : 'Stopped' }}
                    </el-tag>
                  </div>
                </template>
                <div class="status-inline">
                  <span>Total Pairs: <strong>{{ monitorStatus.totalPairs ?? '-' }}</strong></span>
                  <el-divider direction="vertical" />
                  <span>Active Opportunities: <strong>{{ monitorStatus.activeOpportunities ?? '-' }}</strong></span>
                  <el-divider direction="vertical" />
                  <el-button size="small" type="primary" @click="restartMonitor" :loading="restartLoading">
                    Restart Monitor
                  </el-button>
                  <el-button size="small" @click="refreshAll">Refresh</el-button>
                </div>
              </el-card>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="24">
              <el-card>
                <template #header>
                  <div class="card-header-row">
                    <span>Active Opportunities</span>
                    <span class="total-label">Total: {{ activeEvents.length }}</span>
                  </div>
                </template>
                <el-table
                    :data="activeEvents"
                    v-loading="tableLoading"
                    border
                    stripe
                    size="small"
                    style="width: 100%"
                    empty-text="No active opportunities"
                >
                  <el-table-column label="Actions" width="80" align="center">
                    <template #default="{ row }">
                      <el-button size="small" @click="toggleOrders(row)" :type="expandedUuid === row.uuid ? 'warning' : 'primary'">
                        {{ expandedUuid === row.uuid ? 'Hide' : 'Orders' }}
                      </el-button>
                    </template>
                  </el-table-column>
                  <el-table-column label="Pair ID" width="80" prop="similarMarketId" />
                  <el-table-column label="Direction" width="150" prop="direction" />
                  <el-table-column label="Spread" width="100" align="center">
                    <template #default="{ row }">
                      <el-tag :type="spreadTagType(row.spread)" size="small">
                        {{ (row.spread * 100).toFixed(2) }}%
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="Polymarket" min-width="240">
                    <template #default="{ row }">
                      <div class="market-cell">
                        <div class="market-event">
                          <a
                              v-if="row.polymarketEventTicker"
                              :href="polymarketEventUrl(row.polymarketEventTicker)"
                              target="_blank"
                              rel="noopener noreferrer"
                              class="event-link"
                          >{{ row.polymarketEventTitle || row.polymarketEventTicker }}</a>
                          <span v-else class="event-fallback">{{ row.polymarketEventTitle || '-' }}</span>
                          <span class="dot">.</span>
                          <span class="market-title-text">{{ row.polymarketMarketTitle || row.polymarketMarketTicker }}</span>
                        </div>
                        <div v-if="row.polymarketMarketCloseDatetime" class="market-close">
                          {{ formatCloseDate(row.polymarketMarketCloseDatetime) }}
                        </div>
                        <div class="market-tickers">
                          <span class="ticker-pill">[{{ row.polymarketMarketTicker }}]</span>
                        </div>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="Kalshi" min-width="240">
                    <template #default="{ row }">
                      <div class="market-cell">
                        <div class="market-event">
                          <a
                              v-if="row.kalshiEventTicker"
                              :href="kalshiEventUrl(row.kalshiEventTicker)"
                              target="_blank"
                              rel="noopener noreferrer"
                              class="event-link"
                          >{{ row.kalshiEventTitle || row.kalshiEventTicker }}</a>
                          <span v-else class="event-fallback">{{ row.kalshiEventTitle || '-' }}</span>
                          <span class="dot">.</span>
                          <span class="market-title-text">{{ row.kalshiMarketTitle || row.kalshiMarketTicker }}</span>
                        </div>
                        <div v-if="row.kalshiMarketCloseDatetime" class="market-close">
                          {{ formatCloseDate(row.kalshiMarketCloseDatetime) }}
                        </div>
                        <div class="market-tickers">
                          <span class="ticker-pill">[{{ row.kalshiMarketTicker }}]</span>
                        </div>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="PM Yes" width="90" align="center">
                    <template #default="{ row }">{{ row.polymarketYesAsk }}</template>
                  </el-table-column>
                  <el-table-column label="PM No" width="90" align="center">
                    <template #default="{ row }">{{ row.polymarketNoAsk }}</template>
                  </el-table-column>
                  <el-table-column label="KS Yes" width="90" align="center">
                    <template #default="{ row }">{{ row.kalshiYesAsk }}</template>
                  </el-table-column>
                  <el-table-column label="KS No" width="90" align="center">
                    <template #default="{ row }">{{ row.kalshiNoAsk }}</template>
                  </el-table-column>
                  <el-table-column label="Detected" width="170" align="center">
                    <template #default="{ row }">
                      <span class="time-text">{{ formatTime(row.detectedAt) }}</span>
                    </template>
                  </el-table-column>
                </el-table>

                <div v-if="expandedUuid" style="margin-top: 16px;">
                  <el-card v-loading="ordersLoading">
                    <template #header>
                      <span>Orders for {{ expandedUuid }}</span>
                    </template>
                    <el-table :data="ordersForUuid" border size="small" empty-text="No orders yet">
                      <el-table-column label="Platform" prop="platform" width="120" />
                      <el-table-column label="Contract" prop="contractType" width="80" />
                      <el-table-column label="Price" prop="price" width="100" />
                      <el-table-column label="Qty" prop="quantity" width="100" />
                      <el-table-column label="Best Ask" prop="bestAskPrice" width="100" />
                      <el-table-column label="Ask Amount" prop="bestAskAmount" width="120" />
                      <el-table-column label="Order ID" prop="orderId" width="200" />
                      <el-table-column label="Status" width="100">
                        <template #default="{ row }">
                          <el-tag :type="row.status === 'CREATED' ? 'success' : 'danger'" size="small">
                            {{ row.status }}
                          </el-tag>
                        </template>
                      </el-table-column>
                      <el-table-column label="Created" width="170">
                        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
                      </el-table-column>
                    </el-table>
                  </el-card>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { arbitrageApi } from '../api/arbitrage'
import SidebarMenu from '../components/SidebarMenu.vue'

const router = useRouter()
const authStore = useAuthStore()

const monitorStatus = ref({ running: false, totalPairs: 0, activeOpportunities: 0 })
const activeEvents = ref([])
const tableLoading = ref(false)
const restartLoading = ref(false)
const expandedUuid = ref(null)
const ordersForUuid = ref([])
const ordersLoading = ref(false)

const fetchMonitorStatus = async () => {
  try {
    const res = await arbitrageApi.getStatus()
    monitorStatus.value = res.data
  } catch {
    // keep previous
  }
}

const fetchActiveEvents = async () => {
  tableLoading.value = true
  try {
    const res = await arbitrageApi.getActiveEvents()
    activeEvents.value = res.data ?? []
  } catch {
    ElMessage.error('Failed to load active opportunities')
  } finally {
    tableLoading.value = false
  }
}

const restartMonitor = async () => {
  restartLoading.value = true
  try {
    await arbitrageApi.restart()
    ElMessage.success('Monitor restarted')
    await fetchMonitorStatus()
    await fetchActiveEvents()
  } catch {
    ElMessage.error('Failed to restart monitor')
  } finally {
    restartLoading.value = false
  }
}

const refreshAll = () => {
  fetchMonitorStatus()
  fetchActiveEvents()
}

const toggleOrders = async (row) => {
  if (expandedUuid.value === row.uuid) {
    expandedUuid.value = null
    ordersForUuid.value = []
    return
  }
  expandedUuid.value = row.uuid
  ordersLoading.value = true
  try {
    const res = await arbitrageApi.getOrders(row.uuid)
    ordersForUuid.value = res.data ?? []
  } catch {
    ElMessage.error('Failed to load orders')
    ordersForUuid.value = []
  } finally {
    ordersLoading.value = false
  }
}

const spreadTagType = (spread) => {
  if (spread >= 0.05) return 'danger'
  if (spread >= 0.02) return 'warning'
  return 'success'
}

const formatTime = (iso) => {
  if (!iso) return '-'
  return new Date(iso).toLocaleString()
}

const polymarketEventUrl = (ticker) => {
  if (!ticker) return '#'
  return `https://polymarket.com/event/${encodeURIComponent(ticker)}`
}

const kalshiEventUrl = (ticker) => {
  if (!ticker) return '#'
  const series = ticker.split('-')[0]
  return `https://kalshi.com/markets/${encodeURIComponent(series)}/${encodeURIComponent(ticker)}`
}

const formatCloseDate = (iso) => {
  if (!iso) return ''
  const close = new Date(iso)
  if (Number.isNaN(close.getTime())) return ''
  const now = new Date()
  const msPerDay = 24 * 60 * 60 * 1000
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfClose = new Date(close.getFullYear(), close.getMonth(), close.getDate()).getTime()
  const days = Math.round((startOfClose - startOfToday) / msPerDay)
  const dateStr = close.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: '2-digit' })
  const sign = days > 0 ? '+' : ''
  const label = `${sign}${days} day${Math.abs(days) === 1 ? '' : 's'}`
  return `${label} [${dateStr}]`
}

const handleCommand = (command) => {
  if (command === 'logout') {
    authStore.clearAuth()
    ElMessage.success('Logged out successfully')
    router.push('/login')
  }
}

onMounted(() => {
  fetchMonitorStatus()
  fetchActiveEvents()
})
</script>

<style scoped>
.arbitrage-container {
  height: 100vh;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background-color: white;
  border-bottom: 1px solid #e6e6e6;
  padding: 0 20px;
}

.header-left h2 {
  margin: 0;
  color: #333;
}

.user-dropdown {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #606266;
}

.main-content {
  background-color: #f0f2f5;
  padding: 20px;
}

.card-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.total-label {
  font-size: 13px;
  color: #909399;
  font-weight: normal;
}

.status-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #606266;
}

.market-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.market-event {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 2px;
  font-size: 13px;
  line-height: 1.4;
  color: #303133;
}

.event-link {
  color: #409eff;
  text-decoration: none;
}

.event-link:hover {
  text-decoration: underline;
}

.event-fallback {
  color: #303133;
}

.dot {
  color: #909399;
  margin: 0 2px;
}

.market-title-text {
  color: #303133;
}

.market-tickers {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.ticker-pill {
  font-size: 11px;
  color: #909399;
  font-family: monospace;
}

.market-close {
  font-size: 11px;
  color: #606266;
  margin-top: 2px;
}

:deep(.el-table__row > td) {
  vertical-align: top;
}

:deep(.el-table__row > td .cell) {
  vertical-align: top;
}

.time-text {
  font-size: 12px;
  color: #909399;
}
</style>