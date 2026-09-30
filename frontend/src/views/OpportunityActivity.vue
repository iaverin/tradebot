<template>
  <div class="page-container">
    <el-container>
      <SidebarMenu active-index="11" />
      <el-container>
        <el-header class="header">
          <h2>Opportunity Activity</h2>
          <el-dropdown @command="handleCommand">
            <span class="user-dropdown">
              <el-icon><User /></el-icon>
              {{ authStore.username }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu><el-dropdown-item command="logout">Logout</el-dropdown-item></el-dropdown-menu>
            </template>
          </el-dropdown>
        </el-header>

        <el-main class="main-content">
          <el-card v-loading="loading">
            <template #header>
              <div class="card-header">
                <div>
                  <strong>Daily opportunity and order activity</strong>
                  <div class="subtitle">Unique opportunities and their linked created orders, grouped by opportunity date in UTC</div>
                </div>
                <div class="filters">
                  <el-date-picker
                    v-model="dateRange"
                    type="daterange"
                    range-separator="to"
                    start-placeholder="Start date"
                    end-placeholder="End date"
                    format="YYYY-MM-DD"
                    value-format="YYYY-MM-DD"
                    :clearable="false"
                    unlink-panels
                  />
                  <el-button type="primary" @click="loadStats">Apply</el-button>
                </div>
              </div>
            </template>

            <div v-if="stats.length" class="chart-content">
              <div class="chart-wrap">
                <v-chart
                  class="chart"
                  :option="chartOption"
                  autoresize
                  role="img"
                  aria-label="Daily unique opportunities, orders per opportunity, and created order volume"
                />
              </div>
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
import { ArrowDown, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import SidebarMenu from '../components/SidebarMenu.vue'
import { arbitrageApi } from '../api/arbitrage'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const loading = ref(false)
const stats = ref([])

const isoDate = (date) => date.toISOString().slice(0, 10)
const today = new Date()
const nineDaysAgo = new Date(today)
nineDaysAgo.setUTCDate(nineDaysAgo.getUTCDate() - 9)
const dateRange = ref([isoDate(nineDaysAgo), isoDate(today)])

const compactNumber = (value) => new Intl.NumberFormat('en-US', {
  notation: value >= 1000 ? 'compact' : 'standard',
  maximumFractionDigits: 1
}).format(value)
const ratioLabel = (value) => {
  const ratio = Number(value)
  if (!Number.isFinite(ratio)) return ''
  if (ratio === 0) return '0'
  return new Intl.NumberFormat('en-US', {
    maximumFractionDigits: 4
  }).format(ratio)
}
const shortDate = (value) => value.slice(5)
const money = (value) => new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD'
}).format(Number(value))
const chartOption = computed(() => ({
  aria: {
    enabled: true,
    description: 'Combined chart of daily opportunities, orders per opportunity, and created order volume.'
  },
  animationDuration: 300,
  color: ['#79bbff', '#aaaaaa', '#67c23a'],
  grid: { top: 68, right: 32, bottom: 24, left: 24, containLabel: true },
  legend: {
    top: 4,
    right: 8,
    data: [
      'Unique opportunities',
      'Orders per opportunity',
      { name: 'Created order volume', icon: 'circle' }
    ]
  },
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    formatter: (parameters) => {
      const bar = parameters.find(parameter => parameter.seriesName === 'Unique opportunities')
      const line = parameters.find(parameter => parameter.seriesName === 'Orders per opportunity')
      const volume = parameters.find(parameter => parameter.seriesName === 'Created order volume')
      const orderCount = line?.data?.orderCount ?? 0
      return [
        parameters[0]?.axisValue ?? '',
        bar ? `${bar.marker}${bar.seriesName}: <strong>${compactNumber(bar.value)}</strong>` : '',
        line ? `${line.marker}${line.seriesName}: <strong>${ratioLabel(line.value)}</strong> (${compactNumber(orderCount)} orders)` : '',
        volume ? `${volume.marker}${volume.seriesName}: <strong>${money(volume.value)}</strong>` : ''
      ].filter(Boolean).join('<br>')
    }
  },
  xAxis: {
    type: 'category',
    data: stats.value.map(item => item.date),
    axisLabel: { formatter: shortDate, hideOverlap: true },
    axisTick: { alignWithLabel: true }
  },
  yAxis: [
    {
      type: 'value',
      name: 'Opportunities',
      min: 0,
      minInterval: 1,
      axisLabel: { formatter: compactNumber },
      splitLine: { lineStyle: { color: '#e4e7ed' } }
    },
    {
      type: 'value',
      name: 'Orders / opportunity',
      scale: true,
      axisLabel: { formatter: ratioLabel },
      splitLine: { show: false }
    },
    {
      type: 'value',
      min: 0,
      show: false
    }
  ],
  series: [
    {
      name: 'Unique opportunities',
      type: 'bar',
      data: stats.value.map(item => Number(item.opportunityCount)),
      barMaxWidth: 48,
      itemStyle: { borderRadius: [3, 3, 0, 0] },
      emphasis: { focus: 'series', itemStyle: { color: '#409eff' } }
    },
    {
      name: 'Orders per opportunity',
      type: 'line',
      yAxisIndex: 1,
      data: stats.value.map(item => ({
        value: Number(item.ordersPerOpportunity),
        orderCount: Number(item.orderCount)
      })),
      symbol: 'circle',
      symbolSize: 8,
      lineStyle: { color: '#aaaaaa', width: 3 },
      itemStyle: { color: '#aaaaaa', borderColor: '#aaaaaa', borderWidth: 3 },
      emphasis: { focus: 'series' }
    },
    {
      name: 'Created order volume',
      type: 'line',
      yAxisIndex: 2,
      data: stats.value.map(item => Number(item.createdOrderVolume)),
      symbol: 'circle',
      symbolSize: 9,
      showSymbol: true,
      lineStyle: { color: '#67c23a', width: 3 },
      itemStyle: { color: '#67c23a', borderColor: '#ffffff', borderWidth: 2 },
      emphasis: { focus: 'series' }
    }
  ]
}))

const loadStats = async () => {
  if (!dateRange.value?.[0] || !dateRange.value?.[1]) return
  loading.value = true
  try {
    const response = await arbitrageApi.getDailyOpportunityStats(dateRange.value[0], dateRange.value[1])
    stats.value = response.data ?? []
  } catch (error) {
    stats.value = []
    ElMessage.error(error.response?.data?.message || 'Could not load opportunity activity')
  } finally {
    loading.value = false
  }
}

const handleCommand = (command) => {
  if (command === 'logout') {
    authStore.clearAuth()
    router.push('/login')
  }
}

onMounted(loadStats)
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
.chart-content { width: 100%; }
.chart-wrap { width: 100%; overflow-x: auto; }
.chart { width: 100%; min-width: 720px; height: 440px; }
@media (max-width: 800px) {
  .card-header { align-items: flex-start; flex-direction: column; }
  .filters { width: 100%; flex-wrap: wrap; }
}
</style>
