<template>
  <div class="page-container">
    <el-container>
      <SidebarMenu active-index="10" />
      <el-container>
        <el-header class="header">
          <h2>Executed Orders Volume</h2>
          <el-dropdown @command="handleCommand">
            <span class="user-dropdown"><el-icon><User /></el-icon>{{ authStore.username }}<el-icon><ArrowDown /></el-icon></span>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">Logout</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </el-header>
        <el-main class="main-content">
          <el-card v-loading="loading">
            <template #header>
              <div class="card-header">
                <div><strong>Executed orders total by day</strong><div class="subtitle">Order price × quantity, grouped in UTC</div></div>
                <div class="filters">
                  <el-date-picker v-model="dateRange" type="daterange" range-separator="to" start-placeholder="Start date" end-placeholder="End date" format="YYYY-MM-DD" value-format="YYYY-MM-DD" :clearable="false" />
                  <el-button type="primary" @click="loadTotals">Apply</el-button>
                </div>
              </div>
            </template>

            <div class="summary">Total for selected period: <strong>{{ money(periodTotal) }}</strong></div>
            <div v-if="totals.length" class="chart-wrap">
              <v-chart
                class="chart"
                :option="chartOption"
                autoresize
                role="img"
                aria-label="Daily executed order totals in dollars"
              />
            </div>
            <el-empty v-else-if="!loading" description="No chart data" />
          </el-card>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, ArrowDown } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import SidebarMenu from '../components/SidebarMenu.vue'
import { arbitrageApi } from '../api/arbitrage'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const loading = ref(false)
const totals = ref([])
const isoDate = (date) => date.toISOString().slice(0, 10)
const today = new Date()
const thirtyDaysAgo = new Date(today)
thirtyDaysAgo.setUTCDate(thirtyDaysAgo.getUTCDate() - 29)
const dateRange = ref([isoDate(thirtyDaysAgo), isoDate(today)])

const periodTotal = computed(() => totals.value.reduce((sum, item) => sum + Number(item.totalUsd), 0))

const money = (value) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(value))
const compactMoney = (value) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', notation: 'compact' }).format(value)
const shortDate = (value) => value.slice(5)
const chartOption = computed(() => ({
  aria: {
    enabled: true,
    description: 'Line chart of daily executed order totals in US dollars.'
  },
  animationDuration: 300,
  grid: { top: 24, right: 24, bottom: 24, left: 24, containLabel: true },
  tooltip: {
    trigger: 'axis',
    valueFormatter: value => money(value)
  },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: totals.value.map(item => item.date),
    axisLabel: { formatter: shortDate, hideOverlap: true },
    axisTick: { alignWithLabel: true }
  },
  yAxis: {
    type: 'value',
    min: 0,
    axisLabel: { formatter: compactMoney },
    splitLine: { lineStyle: { color: '#e4e7ed' } }
  },
  series: [{
    name: 'Executed order total',
    type: 'line',
    data: totals.value.map(item => Number(item.totalUsd)),
    symbol: 'circle',
    symbolSize: 8,
    showSymbol: true,
    lineStyle: { color: '#409eff', width: 3 },
    itemStyle: { color: '#ffffff', borderColor: '#409eff', borderWidth: 3 },
    emphasis: { focus: 'series' }
  }]
}))

const loadTotals = async () => {
  if (!dateRange.value?.[0] || !dateRange.value?.[1]) return
  loading.value = true
  try {
    const response = await arbitrageApi.getDailyOrderTotals(dateRange.value[0], dateRange.value[1])
    totals.value = response.data ?? []
  } catch (error) {
    totals.value = []
    ElMessage.error(error.response?.data?.message || 'Could not load order totals')
  } finally { loading.value = false }
}
const handleCommand = (command) => { if (command === 'logout') { authStore.clearAuth(); router.push('/login') } }
onMounted(loadTotals)
</script>

<style scoped>
.page-container { height: 100vh; }
.header { display: flex; justify-content: space-between; align-items: center; background: white; border-bottom: 1px solid #e6e6e6; padding: 0 20px; }
.header h2 { margin: 0; color: #333; }
.user-dropdown { display: flex; align-items: center; gap: 8px; cursor: pointer; color: #606266; }
.main-content { background: #f0f2f5; padding: 20px; }
.card-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.subtitle { margin-top: 4px; color: #909399; font-size: 12px; }
.filters { display: flex; align-items: center; gap: 10px; }
.summary { margin-bottom: 12px; color: #606266; }
.summary strong { color: #303133; font-size: 20px; }
.chart-wrap { width: 100%; overflow-x: auto; }
.chart { width: 100%; min-width: 720px; height: 420px; }
@media (max-width: 800px) { .card-header { align-items: flex-start; flex-direction: column; } .filters { width: 100%; flex-wrap: wrap; } }
</style>
