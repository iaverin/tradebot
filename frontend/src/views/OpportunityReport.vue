<template>
  <div class="report-container">
    <el-container>
      <SidebarMenu active-index="7" />
      <el-container>
        <el-header class="header">
          <div class="header-left"><h2>Opportunity Report</h2></div>
          <div class="header-right">
            <el-dropdown @command="handleCommand">
              <span class="user-dropdown">
                <el-icon><User /></el-icon>{{ authStore.username }}<el-icon><ArrowDown /></el-icon>
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
          <el-card>
            <template #header>
              <div class="card-header-row">
                <span>Filters</span>
                <div class="filter-row">
                  <el-date-picker v-model="startDate" type="datetime" placeholder="Start" size="small" format="YYYY-MM-DD HH:mm" />
                  <el-date-picker v-model="endDate" type="datetime" placeholder="End" size="small" format="YYYY-MM-DD HH:mm" />
                  <el-checkbox v-model="onlyWithOrders" size="small">Only with orders</el-checkbox>
                  <el-button size="small" type="primary" @click="loadReport">Apply</el-button>
                </div>
              </div>
            </template>

            <!-- Агрегаты -->
            <el-row :gutter="20" style="margin-bottom:16px">
              <el-col :span="6"><div class="stat-plate"><span>Opportunities</span><strong>{{ totals.opportunities }}</strong></div></el-col>
              <el-col :span="6"><div class="stat-plate"><span>Unique Pairs</span><strong>{{ totals.pairs }}</strong></div></el-col>
              <el-col :span="6"><div class="stat-plate"><span>PM Orders</span><strong>{{ totals.pmOrders }}</strong></div></el-col>
              <el-col :span="6"><div class="stat-plate"><span>KS Orders</span><strong>{{ totals.ksOrders }}</strong></div></el-col>
            </el-row>

            <!-- Таблица -->
            <el-table :data="report" v-loading="loading" border stripe size="small" empty-text="No data"
                      :default-sort="{ prop: 'opportunityCreatedAt', order: 'descending' }">
              <el-table-column label="Created" width="110" sortable prop="opportunityCreatedAt">
                <template #default="{ row }">{{ fmt(row.opportunityCreatedAt) }}</template>
              </el-table-column>
              <el-table-column label="Pair" min-width="300">
                <template #default="{ row }">
                  <div class="pair-cell">
                    <a :href="pmUrl(row.polymarketEventTicker)" target="_blank">{{ row.polymarketEventTitle || row.polymarketEventTicker }}</a>.
                    <span>{{ row.polymarketMarketTitle || row.polymarketMarketTicker }}</span>
                    <span class="sep">/</span>
                    <a :href="ksUrl(row.kalshiEventTicker)" target="_blank">{{ row.kalshiEventTitle || row.kalshiEventTicker }}</a>.
                    <span>{{ row.kalshiMarketTitle || row.kalshiMarketTicker }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="Opps" prop="opportunityCount" width="70" />
              <el-table-column label="w/ Orders" prop="oppsWithOrders" width="80" />
              <el-table-column label="Unexecuted" prop="oppsWithUnexecuted" width="90" />
              <el-table-column label="Close Reasons" align="center">
                <el-table-column label="Spread Below" prop="spreadBelowThresholdCloseCount" width="105" />
                <el-table-column label="Stale Prices" prop="priceDataStaleCloseCount" width="100" />
                <el-table-column label="No Prices" prop="priceDataUnavailableCloseCount" width="90" />
                <el-table-column label="Order Failed" prop="orderCreationFailedCloseCount" width="100" />
                <el-table-column label="Book Price Below" prop="orderbookPriceBelowThresholdCloseCount" width="120" />
                <el-table-column label="Not Enough Amount" prop="orderBookNotEnoughAmountCloseCount" width="135" />
                <el-table-column label="Empty Book" prop="orderbookEmptyCloseCount" width="95" />
                <el-table-column label="Min Cost Not Met" prop="minimumOrderCostNotMetCloseCount" width="120" />
                <el-table-column label="Active Pair Limit" prop="activePairLimitReachedCloseCount" width="120" />
                <el-table-column label="Orders Created" prop="ordersCreatedCloseCount" width="110" />
                <el-table-column label="Unknown" prop="unknownCloseCount" width="85" />
              </el-table-column>
            </el-table>
          </el-card>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { arbitrageApi } from '../api/arbitrage'
import SidebarMenu from '../components/SidebarMenu.vue'
import { User, ArrowDown } from '@element-plus/icons-vue'

const router = useRouter()
const authStore = useAuthStore()

const report = ref([])
const loading = ref(false)
const startDate = ref(null)
const endDate = ref(null)
const onlyWithOrders = ref(false)

const startOfDay = (date) => {
  const d = new Date(date)
  d.setHours(0, 0, 0, 0)
  return d
}

const initDates = () => {
  const now = new Date()
  const tomorrow = new Date(now)
  tomorrow.setDate(tomorrow.getDate() + 1)
  endDate.value = startOfDay(tomorrow)

  const threeDayStart = new Date(now)
  threeDayStart.setDate(threeDayStart.getDate() - 2)
  startDate.value = startOfDay(threeDayStart)
}

const totals = computed(() => ({
  opportunities: report.value.reduce((s, r) => s + r.opportunityCount, 0),
  pairs: new Set(report.value.map(r => r.similarMarketId)).size,
  pmOrders: report.value.reduce((s, r) => s + r.pmOrders, 0),
  ksOrders: report.value.reduce((s, r) => s + r.ksOrders, 0),
}))

const loadReport = async () => {
  loading.value = true
  try {
    const res = await arbitrageApi.getOpportunityReport(
        startDate.value?.toISOString(),
        endDate.value?.toISOString(),
        onlyWithOrders.value
    )
    report.value = res.data ?? []
  } catch { report.value = [] } finally { loading.value = false }
}

const pmUrl = (t) => t ? `https://polymarket.com/event/${encodeURIComponent(t)}` : '#'
const ksUrl = (t) => t ? `https://kalshi.com/${encodeURIComponent(t.split('-')[0])}/${encodeURIComponent(t)}` : '#'
const fmt = (v) => v ? new Date(v).toLocaleString({
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false
}) : '-'

const handleCommand = (c) => { if (c === 'logout') { authStore.clearAuth(); router.push('/login') } }

onMounted(() => {
  initDates()
  loadReport()
})
</script>

<style scoped>
.report-container { height: 100vh; }
.header { display: flex; justify-content: space-between; align-items: center; background: white; border-bottom: 1px solid #e6e6e6; padding: 0 20px; }
.header-left h2 { margin: 0; color: #333; }
.user-dropdown { display: flex; align-items: center; gap: 8px; cursor: pointer; color: #606266; }
.main-content { background: #f0f2f5; padding: 20px; }
.card-header-row { display: flex; justify-content: space-between; align-items: center; }
.filter-row { display: flex; gap: 8px; align-items: center; }
.stat-plate { background: #f5f7fa; border-radius: 8px; padding: 12px; text-align: center; }
.stat-plate span { display: block; font-size: 12px; color: #909399; }
.stat-plate strong { font-size: 22px; color: #303133; }
.pair-cell { font-size: 13px; line-height: 1.5; }
.pair-cell a { color: #409eff; text-decoration: none; }
.sep { color: #909399; margin: 0 4px; }
</style>
