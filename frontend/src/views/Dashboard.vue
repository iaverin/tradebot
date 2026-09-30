<template>
  <div class="dashboard-container">
    <el-container>
      <SidebarMenu active-index="1" />

      <el-container>
        <el-header class="header">
          <div class="header-left">
            <h2>Dashboard</h2>
            <el-tag
              :type="tradingEnabled ? 'success' : 'danger'"
              size="small"
              class="trading-indicator"
            >
              Trading: {{ tradingEnabled ? 'ON' : 'OFF' }}
            </el-tag>
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

          <el-row :gutter="20" class="daily-orders-row">
            <el-col :span="24">
              <el-card v-loading="dailyOrderTotalsLoading">
                <template #header>
                  <div class="card-header-row">
                    <div>
                      <span>Executed Orders</span>
                      <div class="card-subtitle">Daily volume in UTC</div>
                    </div>
                    <el-button size="small" @click="fetchDailyOrderTotals">Refresh</el-button>
                  </div>
                </template>
                <div class="daily-orders-grid">
                  <div class="daily-order-total">
                    <span class="daily-order-label">Today</span>
                    <span class="daily-order-date">{{ todayUtc }}</span>
                    <span class="daily-order-value">{{ formatBalance(todayOrderTotal) }}</span>
                  </div>
                  <div class="daily-order-total">
                    <span class="daily-order-label">Yesterday</span>
                    <span class="daily-order-date">{{ yesterdayUtc }}</span>
                    <span class="daily-order-value">{{ formatBalance(yesterdayOrderTotal) }}</span>
                  </div>
                </div>
              </el-card>
            </el-col>
          </el-row>

        <el-row :gutter="20" class="equal-height-row portfolio-row">
          <el-col :span="12">
            <el-card v-loading="portfolioLoading">
              <template #header>
                <div class="card-header-row">
                  <span>Kalshi Portfolio</span>
                  <el-button size="small" @click="fetchPortfolio">Refresh</el-button>
                </div>
              </template>
              <div class="portfolio-grid">
                <div class="portfolio-total">
                  <span class="portfolio-total-label">Total Portfolio</span>
                  <span class="portfolio-total-value">{{ formatBalance(kalshiPortfolio?.totalPortfolioUsd) }}</span>
                </div>
                <div class="portfolio-row-sub">
                  <span class="status-label">In Cash</span>
                  <span class="status-value">{{ formatBalance(kalshiPortfolio?.inCashUsd) }}</span>
                </div>
                <div class="portfolio-row-sub">
                  <span class="status-label">In Orders</span>
                  <span class="status-value">{{ formatBalance(kalshiPortfolio?.inOrdersUsd) }}</span>
                </div>
                <div class="portfolio-row-sub">
                  <span class="status-label">In Assets</span>
                  <span class="status-value">{{ formatBalance(kalshiPortfolio?.inAssetsUsd) }}</span>
                </div>
                <div class="portfolio-row-sub">
                  <span class="status-label">Resolved Profit</span>
                  <span class="status-value stat-green">{{ formatBalance(kalshiPortfolio?.resolvedProfit) }}</span>
                </div>
                <div class="portfolio-row-sub">
                  <span class="status-label">Resolved Loss</span>
                  <span class="status-value stat-red">{{ formatBalance(kalshiPortfolio?.resolvedLoss) }}</span>
                </div>
              </div>
            </el-card>
          </el-col>

          <el-col :span="12">
            <el-card v-loading="portfolioLoading">
              <template #header>
                <div class="card-header-row">
                  <span>Polymarket Portfolio</span>
                  <span></span>
                </div>
              </template>
              <div class="portfolio-grid">
                <div class="portfolio-total">
                  <span class="portfolio-total-label">Total Portfolio</span>
                  <span class="portfolio-total-value">{{ formatBalance(polymarketPortfolio?.totalPortfolioUsd) }}</span>
                </div>
                <div class="portfolio-row-sub">
                  <span class="status-label">In Cash</span>
                  <span class="status-value">{{ formatBalance(polymarketPortfolio?.inCashUsd) }}</span>
                </div>
                <div class="portfolio-row-sub">
                  <span class="status-label">In Orders</span>
                  <span class="status-value">{{ formatBalance(polymarketPortfolio?.inOrdersUsd) }}</span>
                </div>
                <div class="portfolio-row-sub">
                  <span class="status-label">In Assets</span>
                  <span class="status-value">{{ formatBalance(polymarketPortfolio?.inAssetsUsd) }}</span>
                </div>
                <div class="portfolio-row-sub">
                  <span class="status-label">Resolved Profit</span>
                  <span class="status-value stat-green">{{ formatBalance(polymarketPortfolio?.resolvedProfit) }}</span>
                </div>
                <div class="portfolio-row-sub">
                  <span class="status-label">Resolved Loss</span>
                  <span class="status-value stat-red">{{ formatBalance(polymarketPortfolio?.resolvedLoss) }}</span>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>

          <el-row :gutter="20" class="equal-height-row">
            <el-col :span="8">
              <el-card>
                <template #header>Monitor Status</template>
                <div class="status-grid">
                  <div class="status-row">
                    <span class="status-label">Status</span>
                    <el-tag :type="monitorStatus.running ? 'success' : 'danger'" size="small">
                      {{ monitorStatus.running ? 'Running' : 'Stopped' }}
                    </el-tag>
                  </div>
                  <div class="status-row">
                    <span class="status-label">Total Pairs</span>
                    <span class="status-value">{{ monitorStatus.totalPairs ?? '-' }}</span>
                  </div>
                  <div class="status-row">
                    <span class="status-label">Active Opportunities</span>
                    <span class="status-value">{{ monitorStatus.activeOpportunities ?? '-' }}</span>
                  </div>
                </div>
              </el-card>
            </el-col>

            <el-col :span="8">
              <el-card v-loading="datasourceLoading">
                <template #header>Records by venues</template>
                <div class="status-grid">
                  <div
                    v-for="row in datasourceCounts"
                    :key="row.datasource"
                    class="status-row"
                  >
                    <span class="status-label">{{ row.datasource }}</span>
                    <span class="status-value">{{ formatCount(row.count) }}</span>
                  </div>
                  <div class="status-row">
                    <span class="status-label">Total</span>
                    <span class="status-value">{{ formatCount(totalRecords) }}</span>
                  </div>
                  <div v-if="!datasourceLoading && datasourceCounts.length === 0" class="status-row">
                    <span class="status-label">No records found</span>
                  </div>
                </div>
              </el-card>
            </el-col>

            <el-col :span="8">
              <el-card>
                <template #header>Similar Markets Summary</template>
                <div class="status-grid">
                  <div class="status-row">
                    <span class="status-label">Similar Events</span>
                    <span class="status-value">{{ similarCounts?.similar_events_count?.toLocaleString() ?? '-' }}</span>
                  </div>
                  <div class="status-row">
                    <span class="status-label">Similar Markets</span>
                    <span class="status-value">{{ similarCounts?.similar_markets_count?.toLocaleString() ?? '-' }}</span>
                  </div>
                </div>
              </el-card>
            </el-col>
          </el-row>
          <el-row :gutter="20" style="margin-top: 20px;">
            <el-col :span="24">
              <el-card v-loading="ordersLoading">
                <template #header>
                  <div class="card-header-row">
                    <span>Recent Orders</span>
                    <el-button size="small" @click="fetchOrders">Refresh</el-button>
                  </div>
                </template>

                <el-row :gutter="20" style="margin-bottom: 16px;">
                  <el-col :span="4">
                    <div class="stat-mini">
                      <div class="stat-mini-label">Total</div>
                      <div class="stat-mini-value">{{ ordersStats.totalOrders ?? '-' }}</div>
                    </div>
                  </el-col>
                  <el-col :span="4">
                    <div class="stat-mini">
                      <div class="stat-mini-label">Executed</div>
                      <div class="stat-mini-value stat-green">{{ ordersStats.executedOrders ?? '-' }}</div>
                    </div>
                  </el-col>
                  <el-col :span="4">
                    <div class="stat-mini">
                      <div class="stat-mini-label">Placed</div>
                      <div class="stat-mini-value stat-blue">{{ ordersStats.placedOrders ?? '-' }}</div>
                    </div>
                  </el-col>
                  <el-col :span="4">
                    <div class="stat-mini">
                      <div class="stat-mini-label">Canceled</div>
                      <div class="stat-mini-value stat-gray">{{ ordersStats.canceledOrders ?? '-' }}</div>
                    </div>
                  </el-col>
                  <el-col :span="4">
                    <div class="stat-mini">
                      <div class="stat-mini-label">Error</div>
                      <div class="stat-mini-value stat-red">{{ ordersStats.errorOrders ?? '-' }}</div>
                    </div>
                  </el-col>
                  <el-col :span="4">
                    <div class="stat-mini">
                      <div class="stat-mini-label">Spent</div>
                      <div class="stat-mini-value stat-green">${{ formatSpent(ordersStats.totalSpent) }}</div>
                    </div>
                  </el-col>
                </el-row>

                <el-table :data="groupedRecentOrders" border stripe size="small" style="width: 100%" empty-text="No orders yet" :span-method="spanMethod">
                  <el-table-column label="Opportunity" width="300">
                    <template #default="{ row }">
                      <div class="opp-cell">
                        <div class="opp-created">{{ fmtFull(row.createdAt) }}</div>
                        <div class="opp-uuid">{{ row.opportunityUuid || '-' }}</div>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="Platform" prop="platform" width="100" />
                  <el-table-column label="Event / Market" min-width="200">
                    <template #default="{ row }">
                      <div class="pair-cell">
                        <a v-if="row.eventTicker" :href="venueEventUrl(row.platform, row.eventTicker)" target="_blank" class="venue-link">{{ row.eventTitle || row.eventTicker }}</a>
                        <span v-else>-</span>
                        <span v-if="row.marketTitle || row.marketTicker">. {{ row.marketTitle || row.marketTicker }}</span>
                      </div>
                      <div v-if="row.orderId" class="order-id-row">
                        <a :href="orderRequestsUrl(row.platform, row.orderId)" class="order-id-link">{{ row.orderId }}</a>
                      </div>

                    </template>
                  </el-table-column>
                  <el-table-column label="Contract" prop="contractType" width="80" />
                  <el-table-column label="Price" width="90">
                    <template #default="{ row }">${{ row.price }}</template>
                  </el-table-column>
                  <el-table-column label="Qty" prop="quantity" width="70" />
                  <el-table-column label="Cost" width="90">
                    <template #default="{ row }">${{ (row.price * row.quantity).toFixed(2) }}</template>
                  </el-table-column>
                  <el-table-column label="Status" width="100">
                    <template #default="{ row }">
                      <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="Executed" width="160">
                    <template #default="{ row }">{{ formatTime(row.executedAt) }}</template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
          </el-row>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { arbitrageApi } from '../api/arbitrage'
import { similarMarketsApi } from '../api/similarMarkets'
import { balanceApi } from '../api/balance'
import { getTradingEnabled } from '../api/settings'
import { portfolioApi } from '../api/portfolio'
import SidebarMenu from '../components/SidebarMenu.vue'
import { venueEventUrl } from '../composables/venueUtils'

import { orderStatusPath, orderRequestsUrl, fmtFull, statusTagType } from '../composables/useOrderUtils'

const router = useRouter()
const authStore = useAuthStore()
const ordersStats = ref({})
const recentOrders = ref([])
const ordersLoading = ref(false)
const dailyOrderTotals = ref([])
const dailyOrderTotalsLoading = ref(false)
const monitorStatus = ref({ running: false, totalPairs: 0, activeOpportunities: 0 })
const datasourceCounts = ref([])
const datasourceLoading = ref(false)
const similarCounts = ref(null)
const polymarketBalance = ref(null)
const kalshiBalance = ref(null)
const balanceLoading = ref(false)
const tradingEnabled = ref(false)

const portfolioLoading = ref(false)
const kalshiPortfolio = ref(null)
const polymarketPortfolio = ref(null)

const isoDate = (date) => date.toISOString().slice(0, 10)
const currentDate = new Date()
const previousDate = new Date(currentDate)
previousDate.setUTCDate(previousDate.getUTCDate() - 1)
const todayUtc = isoDate(currentDate)
const yesterdayUtc = isoDate(previousDate)

const dailyTotalFor = (date) => {
  const total = dailyOrderTotals.value.find((item) => item.date === date)?.totalUsd
  return total == null ? 0 : total
}
const todayOrderTotal = computed(() => dailyTotalFor(todayUtc))
const yesterdayOrderTotal = computed(() => dailyTotalFor(yesterdayUtc))

const totalRecords = computed(() =>
  datasourceCounts.value.reduce((sum, row) => sum + Number(row.count ?? 0), 0)
)

const formatCount = (value) => Number(value ?? 0).toLocaleString()

const formatBalance = (value) =>
  value == null
    ? '-'
    : Number(value).toLocaleString('en-US', { style: 'currency', currency: 'USD' })

const fetchTradingEnabled = async () => {
  try {
    const { data } = await getTradingEnabled()
    tradingEnabled.value = data.tradingEnabled
  } catch {
    // silently ignore — keep default false
  }
}

const fetchMonitorStatus = async () => {
  try {
    const res = await arbitrageApi.getStatus()
    monitorStatus.value = res.data
  } catch {
  }
}

const fetchDatasourceCounts = async () => {
  datasourceLoading.value = true
  try {
    const res = await similarMarketsApi.countFetchedMarkets()
    datasourceCounts.value = (res.data ?? []).map((row) => ({
      datasource: row.datasource ?? row.DATASOURCE,
      count: row.count ?? row.COUNT,
    }))
  } catch {
    ElMessage.error('Failed to load datasource counts')
  } finally {
    datasourceLoading.value = false
  }
}

const fetchSimilarCounts = async () => {
  try {
    const res = await similarMarketsApi.countSimilarMarkets()
    similarCounts.value = res.data
  } catch {
    // keep previous value on transient errors
  }
}


const fetchBalance = async () => {
  balanceLoading.value = true
  try {
    const [kalshi, polymarket] = await Promise.all([
      balanceApi.balanceKalshi(),
      balanceApi.balancePolymarket(),
    ])

    kalshiBalance.value = kalshi.data.balanceDollars
    polymarketBalance.value = polymarket.data.balanceDollars
  } catch {
    // keep previous value on transient errors
  } finally {
    balanceLoading.value = false
  }
}


const fetchPortfolio = async () => {
  portfolioLoading.value = true
  try {
    const { data } = await portfolioApi.getDashboard()
    kalshiPortfolio.value = data.kalshi
    polymarketPortfolio.value = data.polymarket
  } catch {
    // keep previous values on transient errors
  } finally {
    portfolioLoading.value = false
  }
}

const handleCommand = (command) => {
  if (command === 'logout') {
    authStore.clearAuth()
    ElMessage.success('Logged out successfully')
    router.push('/login')
  }
}

const fetchOrders = async () => {
  ordersLoading.value = true
  try {
    const [statsRes, ordersRes] = await Promise.all([
      arbitrageApi.getOrdersStats(),
      arbitrageApi.getRecentOrders(20),
    ])
    ordersStats.value = statsRes.data
    recentOrders.value = ordersRes.data ?? []
  } catch {
    // keep previous
  } finally {
    ordersLoading.value = false
  }
}

const fetchDailyOrderTotals = async () => {
  dailyOrderTotalsLoading.value = true
  try {
    const { data } = await arbitrageApi.getDailyOrderTotals(yesterdayUtc, todayUtc)
    dailyOrderTotals.value = data ?? []
  } catch {
    // keep previous values on transient errors
  } finally {
    dailyOrderTotalsLoading.value = false
  }
}

const groupedRecentOrders = computed(() =>
  [...recentOrders.value].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
)

const spanMethod = ({ row, column, rowIndex, columnIndex }) => {
  if (columnIndex === 0) {
    const uuid = row.opportunityUuid
    const data = groupedRecentOrders.value
    let first = rowIndex
    while (first > 0 && data[first - 1]?.opportunityUuid === uuid) first--
    let count = 1
    while (first + count < data.length && data[first + count]?.opportunityUuid === uuid) count++
    if (rowIndex === first) return { rowspan: count, colspan: 1 }
    return { rowspan: 0, colspan: 0 }
  }
  return { rowspan: 1, colspan: 1 }
}

const formatSpent = (value) => value != null ? Number(value).toFixed(2) : '-'

const formatTime = (iso) => {
  if (!iso) return '-'
  return new Date(iso).toLocaleString()
}

onMounted(() => {
  fetchTradingEnabled()
  fetchBalance()
  fetchPortfolio()
  fetchMonitorStatus()
  fetchDatasourceCounts()
  fetchSimilarCounts()
  fetchOrders()
  fetchDailyOrderTotals()
})
</script>

<style scoped>
.dashboard-container {
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

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.trading-indicator {
  font-weight: 600;
  letter-spacing: 0.5px;
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

.equal-height-row {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
}

.balance-row {
  margin-bottom: 20px;
}

.balance-amount {
  font-size: 28px;
  font-weight: 700;
  color: #303133;
}

.equal-height-row :deep(.el-col) {
  display: flex;
}

.equal-height-row :deep(.el-card) {
  width: 100%;
  display: flex;
  flex-direction: column;
}

.equal-height-row :deep(.el-card__body) {
  flex: 1;
}

.status-grid {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.status-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.status-label {
  color: #606266;
  font-size: 14px;
}

.status-value {
  font-weight: 600;
  font-size: 15px;
  color: #303133;
}

.card-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.stat-mini {
  text-align: center;
}

.stat-mini-label {
  font-size: 12px;
  color: #909399;
}

.stat-mini-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.stat-green { color: #67C23A; }
.stat-blue { color: #409EFF; }
.stat-gray { color: #909399; }
.stat-red { color: #F56C6C; }

.daily-orders-row {
  margin-bottom: 20px;
}

.card-subtitle {
  margin-top: 3px;
  color: #909399;
  font-size: 12px;
}

.daily-orders-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}

.daily-order-total {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.daily-order-label {
  color: #606266;
  font-weight: 600;
}

.daily-order-date {
  color: #909399;
  font-size: 12px;
}

.daily-order-value {
  color: #67C23A;
  font-size: 24px;
  font-weight: 700;
}

.portfolio-row {
  margin-bottom: 20px;
}

.portfolio-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.portfolio-total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 4px;
}

.portfolio-total-label {
  font-size: 14px;
  color: #606266;
  font-weight: 600;
}

.portfolio-total-value {
  font-size: 28px;
  font-weight: 700;
  color: #303133;
}

.portfolio-row-sub {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-left: 8px;
}

.venue-link { color: #409eff; text-decoration: none; }
.venue-link:hover { text-decoration: underline; }
.pair-cell { font-size: 13px; line-height: 1.5; }
.opp-cell { line-height: 1.6; }
.opp-created { font-size: 13px; color: #303133; }
.opp-uuid { font-family: monospace; font-size: 11px; color: #909399; }
.order-id-link { font-family: monospace; font-size: 11px; color: #909399; text-decoration: none; }
.order-id-link:hover { color: #409eff; text-decoration: underline; }

</style>
