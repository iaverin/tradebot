<template>
  <div class="orders-container">
    <el-container>
      <SidebarMenu active-index="6" />
      <el-container>
        <el-header class="header">
          <div class="header-left"><h2>Orders</h2></div>
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
                <span>Orders</span>
                <div class="filter-row">
                  <el-select v-model="filterStatus" placeholder="Status" clearable size="small" style="width:140px" @change="loadOrders">
                    <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
                  </el-select>
                  <el-select v-model="filterPlatform" placeholder="Platform" clearable size="small" style="width:140px" @change="loadOrders">
                    <el-option label="KALSHI" value="KALSHI" />
                    <el-option label="POLYMARKET" value="POLYMARKET" />
                  </el-select>
                  <el-button size="small" @click="loadOrders">Refresh</el-button>
                </div>
              </div>
            </template>
            <el-table :data="groupedOrders" v-loading="loading" border stripe size="small" empty-text="No orders" :span-method="spanMethod">
              <el-table-column label="Opportunity" width="300">
                <template #default="{ row }">
                  <div class="opp-cell">
                    <div class="opp-created">{{ fmtFull(row.createdAt) }}</div>
                    <div class="opp-uuid">{{ row.opportunityUuid || '-' }}</div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="Platform" prop="platform" width="110" />
              <el-table-column label="Event / Market" min-width="280">
                <template #default="{ row }">
                  <div class="pair-cell">
                    <div>
                      <a v-if="row.eventTicker" :href="venueEventUrl(row.platform, row.eventTicker)" target="_blank" class="venue-link">{{ row.eventTitle || row.eventTicker }}</a>
                      <span v-else>-</span>
                      <span v-if="row.marketTitle || row.marketTicker">. {{ row.marketTitle || row.marketTicker }}</span>
                    </div>
                    <div v-if="row.orderId" class="order-id-row">
                      <a :href="orderRequestsUrl(row.platform, row.orderId)" class="order-id-link">{{ row.orderId }}</a>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="Contract" prop="contractType" width="80" />
              <el-table-column label="Price" width="90"><template #default="{row}">${{ row.price }}</template></el-table-column>
              <el-table-column label="Qty" prop="quantity" width="70" />
              <el-table-column label="Cost" width="100"><template #default="{row}">${{ (row.price * row.quantity).toFixed(2) }}</template></el-table-column>
              <el-table-column label="Status" width="100">
                <template #default="{row}"><el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag></template>
              </el-table-column>
              <el-table-column label="Executed" width="160"><template #default="{row}">{{ fmt(row.executedAt) }}</template></el-table-column>
            </el-table>
            <div class="pagination-row">
              <el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="totalElements" layout="prev, pager, next, total" background @current-change="loadOrders" />
            </div>
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
import { venueEventUrl } from '../composables/venueUtils'
import { orderStatusPath, orderRequestsUrl, fmtFull, statusTagType } from '../composables/useOrderUtils'

const router = useRouter()
const authStore = useAuthStore()

const orders = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(50)
const totalElements = ref(0)
const filterStatus = ref(null)
const filterPlatform = ref(null)
const statuses = ['CREATED', 'PENDING', 'PLACED', 'EXECUTED', 'CANCELED', 'ERROR']

const loadOrders = async () => {
  loading.value = true
  try {
    const res = await arbitrageApi.getOrdersPage(currentPage.value - 1, pageSize.value, filterStatus.value, filterPlatform.value)
    orders.value = res.data.content ?? []
    totalElements.value = res.data.totalElements ?? 0
  } catch { orders.value = [] } finally { loading.value = false }
}

const groupedOrders = computed(() =>
  [...orders.value].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
)

const spanMethod = ({ row, column, rowIndex, columnIndex }) => {
  if (columnIndex === 0) {
    const uuid = row.opportunityUuid
    const data = groupedOrders.value
    // find first occurrence
    let first = rowIndex
    while (first > 0 && data[first - 1]?.opportunityUuid === uuid) first--
    // count consecutive with same uuid
    let count = 1
    while (first + count < data.length && data[first + count]?.opportunityUuid === uuid) count++
    if (rowIndex === first) return { rowspan: count, colspan: 1 }
    return { rowspan: 0, colspan: 0 }
  }
  return { rowspan: 1, colspan: 1 }
}

const fmt = (iso) => iso ? new Date(iso).toLocaleString() : '-'

const handleCommand = (cmd) => { if (cmd === 'logout') { authStore.clearAuth(); router.push('/login') } }

onMounted(() => loadOrders())
</script>

<style scoped>
.orders-container { height: 100vh; }
.header { display: flex; justify-content: space-between; align-items: center; background: white; border-bottom: 1px solid #e6e6e6; padding: 0 20px; }
.header-left h2 { margin: 0; color: #333; }
.user-dropdown { display: flex; align-items: center; gap: 8px; cursor: pointer; color: #606266; }
.main-content { background: #f0f2f5; padding: 20px; }
.card-header-row { display: flex; justify-content: space-between; align-items: center; }
.filter-row { display: flex; gap: 8px; }
.pagination-row { margin-top: 16px; display: flex; justify-content: flex-end; }
.venue-link { color: #409eff; text-decoration: none; }
.venue-link:hover { text-decoration: underline; }
.pair-cell { font-size: 13px; line-height: 1.5; }
.opp-cell { line-height: 1.6; }
.opp-created { font-size: 13px; color: #303133; }
.opp-uuid { font-family: monospace; font-size: 11px; color: #909399; }
.order-id-row { margin-top: 2px; }
.order-id-link { font-family: monospace; font-size: 11px; color: #909399; text-decoration: none; }
.order-id-link:hover { color: #409eff; text-decoration: underline; }
</style>