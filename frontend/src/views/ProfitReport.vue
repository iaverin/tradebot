<template>
  <div class="report-container">
    <el-container>
      <SidebarMenu active-index="8" />
      <el-container>
        <el-header class="header">
          <div class="header-left"><h2>Profit Report</h2></div>
          <div class="header-right">
            <el-dropdown @command="handleCommand">
              <span class="user-dropdown"><el-icon><User /></el-icon>{{ authStore.username }}<el-icon><ArrowDown /></el-icon></span>
              <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">Logout</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
          </div>
        </el-header>
        <el-main class="main-content">
          <el-card>
            <template #header>
              <div class="card-header-row">
                <span>Filters</span>
                <div class="filter-row">
                  <el-select v-model="closeSource" size="small" style="width:140px">
                    <el-option label="Polymarket" value="POLYMARKET" />
                    <el-option label="Kalshi" value="KALSHI" />
                  </el-select>
                  <el-date-picker v-model="startDate" type="datetime" placeholder="Start" size="small" format="YYYY-MM-DD HH:mm" />
                  <el-date-picker v-model="endDate" type="datetime" placeholder="End" size="small" format="YYYY-MM-DD HH:mm" />
                  <el-button size="small" type="primary" @click="loadReport">Apply</el-button>
                </div>
              </div>
            </template>

            <el-row :gutter="20" style="margin-bottom:16px">
              <el-col :span="6"><div class="stat-plate"><span>Order Total</span><strong>${{ totals.orderTotal }}</strong></div></el-col>
              <el-col :span="6"><div class="stat-plate"><span>Filled Total</span><strong>${{ totals.filledTotal }}</strong></div></el-col>
              <el-col :span="6"><div class="stat-plate"><span>Order Profit</span><strong>${{ totals.orderProfit }}</strong></div></el-col>
              <el-col :span="6"><div class="stat-plate"><span>Filled Profit</span><strong>${{ totals.filledProfit }}</strong></div></el-col>
            </el-row>

            <el-table :data="report" v-loading="loading" border stripe size="small" empty-text="No data">
              <el-table-column label="Close Date" width="120"><template #default="{r}">{{ fmt(r.closeDate) }}</template></el-table-column>
              <el-table-column label="Pairs" prop="uniquePairs" width="80" />
              <el-table-column label="Opps" prop="opportunityCount" width="80" />
              <el-table-column label="Order $" width="100"><template #default="{r}">${{ r.orderTotal?.toFixed(2) }}</template></el-table-column>
              <el-table-column label="Filled $" width="100"><template #default="{r}">${{ r.filledTotal?.toFixed(2) }}</template></el-table-column>
              <el-table-column label="Order Profit" width="110"><template #default="{r}">${{ r.orderProfit?.toFixed(2) }}</template></el-table-column>
              <el-table-column label="Filled Profit" width="110"><template #default="{r}">${{ r.filledProfit?.toFixed(2) }}</template></el-table-column>
            </el-table>
          </el-card>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { arbitrageApi } from '../api/arbitrage'
import SidebarMenu from '../components/SidebarMenu.vue'
import { User, ArrowDown } from '@element-plus/icons-vue'

const router = useRouter()
const authStore = useAuthStore()

const report = ref([])
const loading = ref(false)
const closeSource = ref('POLYMARKET')
const startDate = ref(null)
const endDate = ref(null)

const totals = computed(() => ({
  orderTotal: report.value.reduce((s, r) => s + (r.orderTotal ?? 0), 0).toFixed(2),
  filledTotal: report.value.reduce((s, r) => s + (r.filledTotal ?? 0), 0).toFixed(2),
  orderProfit: report.value.reduce((s, r) => s + (r.orderProfit ?? 0), 0).toFixed(2),
  filledProfit: report.value.reduce((s, r) => s + (r.filledProfit ?? 0), 0).toFixed(2),
}))

const loadReport = async () => {
  loading.value = true
  try {
    const res = await arbitrageApi.getProfitReport(
        closeSource.value,
        startDate.value?.toISOString(),
        endDate.value?.toISOString()
    )
    report.value = res.data ?? []
  } catch { report.value = [] } finally { loading.value = false }
}

const fmt = (v) => v ? new Date(v).toLocaleDateString() : '-'
const handleCommand = (c) => { if (c === 'logout') { authStore.clearAuth(); router.push('/login') } }
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
</style>