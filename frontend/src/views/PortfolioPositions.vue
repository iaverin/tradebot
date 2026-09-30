<template>
  <div class="positions-container">
    <el-container>
      <SidebarMenu active-index="12" />
      <el-container>
        <el-header class="header">
          <div class="header-left"><h2>Open Positions</h2></div>
          <div class="header-right">
            <el-dropdown @command="handleCommand">
              <span class="user-dropdown">
                <el-icon><User /></el-icon>{{ authStore.username }}<el-icon><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu><el-dropdown-item command="logout">Logout</el-dropdown-item></el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </el-header>

        <el-main class="main-content">
          <el-card>
            <template #header>
              <div class="card-header-row">
                <div class="venue-filter">
                  <span class="filter-label">Primary venue</span>
                  <el-select v-model="selectedVenue" style="width: 170px" @change="changeVenue">
                    <el-option label="Polymarket" value="POLYMARKET" />
                    <el-option label="Kalshi" value="KALSHI" />
                  </el-select>
                </div>
                <div class="refresh-controls">
                  <span class="last-updated">Last updated: {{ formattedLastUpdated }}</span>
                  <el-button type="primary" :loading="refreshing" :disabled="refreshing" @click="refreshPortfolio">
                    Update portfolio
                  </el-button>
                </div>
              </div>
            </template>

            <el-table
              v-loading="loading"
              :data="tableRows"
              border
              size="small"
              empty-text="No open positions"
              :row-class-name="rowClassName"
              class="positions-table"
            >
              <el-table-column label="Relation" width="95">
                <template #default="{ row }">
                  <el-tag v-if="row.isRelated" type="info" size="small">Related</el-tag>
                  <span v-else class="primary-label">Primary</span>
                </template>
              </el-table-column>
              <el-table-column label="Venue" prop="venue" width="125" />
              <el-table-column label="Event / Market" min-width="340">
                <template #default="{ row }">
                  <div class="market-cell">
                    <a
                      v-if="row.eventTicker"
                      :href="venueEventUrl(row.venue, row.eventTicker)"
                      target="_blank"
                      rel="noopener noreferrer"
                      class="venue-link"
                    >{{ row.eventTitle || row.eventTicker }}</a>
                    <span v-else>{{ row.eventTitle || '-' }}</span>
                    <span v-if="row.marketTitle || row.marketTicker">. {{ row.marketTitle || row.marketTicker }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="Outcome" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.outcome === 'YES' ? 'success' : 'danger'" size="small">
                    {{ row.outcome }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="Quantity" width="150" align="right">
                <template #default="{ row }">{{ formatQuantity(row.quantity) }}</template>
              </el-table-column>
              <el-table-column label="Spent" width="140" align="right">
                <template #default="{ row }">{{ formatUsd(row.spentUsd) }}</template>
              </el-table-column>
              <el-table-column label="Current value" width="150" align="right">
                <template #default="{ row }">{{ formatUsd(row.currentValueUsd) }}</template>
              </el-table-column>
            </el-table>

            <div class="pagination-row">
              <el-pagination
                v-model:current-page="currentPage"
                :page-size="pageSize"
                :total="totalElements"
                layout="prev, pager, next, total"
                background
                @current-change="loadPositions"
              />
            </div>
          </el-card>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, User } from '@element-plus/icons-vue'
import SidebarMenu from '../components/SidebarMenu.vue'
import { portfolioApi } from '../api/portfolio'
import { useAuthStore } from '../stores/auth'
import { venueEventUrl } from '../composables/venueUtils'

const router = useRouter()
const authStore = useAuthStore()
const selectedVenue = ref('POLYMARKET')
const groups = ref([])
const currentPage = ref(1)
const pageSize = ref(50)
const totalElements = ref(0)
const lastSuccessfulRefreshAt = ref(null)
const loading = ref(false)
const refreshing = ref(false)

const tableRows = computed(() => groups.value.flatMap((group) => [
  { ...group.primary, groupId: group.groupId, isRelated: false },
  ...(group.related ?? []).map((position) => ({
    ...position,
    groupId: group.groupId,
    isRelated: true,
  })),
]))

const formattedLastUpdated = computed(() => {
  if (!lastSuccessfulRefreshAt.value) return 'Never'
  const date = new Date(lastSuccessfulRefreshAt.value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString()
})

const loadPositions = async () => {
  loading.value = true
  try {
    const response = await portfolioApi.getPositions(
      selectedVenue.value,
      currentPage.value - 1,
      pageSize.value,
    )
    groups.value = response.data.content ?? []
    totalElements.value = response.data.totalElements ?? 0
    lastSuccessfulRefreshAt.value = response.data.lastSuccessfulRefreshAt ?? null
  } catch (error) {
    groups.value = []
    totalElements.value = 0
    lastSuccessfulRefreshAt.value = null
    ElMessage.error(error.response?.data?.message || 'Failed to load open positions')
  } finally {
    loading.value = false
  }
}

const changeVenue = () => {
  currentPage.value = 1
  loadPositions()
}

const refreshPortfolio = async () => {
  refreshing.value = true
  try {
    const response = await portfolioApi.refreshPositions()
    if (response.data.allSucceeded) {
      ElMessage.success('Portfolio updated')
    } else {
      const failedVenues = (response.data.venues ?? [])
        .filter((venue) => venue.status === 'FAILED')
        .map((venue) => venue.venue)
      ElMessage.warning(`Portfolio updated with failures: ${failedVenues.join(', ')}`)
    }
  } catch (error) {
    if (error.response?.status === 409) {
      ElMessage.warning('A portfolio update is already in progress')
    } else {
      ElMessage.error(error.response?.data?.message || 'Failed to update portfolio')
    }
  } finally {
    await loadPositions()
    refreshing.value = false
  }
}

const formatQuantity = (value) => {
  if (value === null || value === undefined || value === '') return '-'
  const text = String(value)
  return text.includes('.') ? text.replace(/\.?0+$/, '') : text
}

const formatUsd = (value) => {
  if (value === null || value === undefined || value === '') return '-'
  const number = Number(value)
  if (!Number.isFinite(number)) return '-'
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(number)
}

const rowClassName = ({ row, rowIndex }) => {
  const classes = []
  if (row.isRelated) classes.push('related-row')
  if (!row.isRelated && rowIndex > 0) classes.push('group-start-row')
  return classes.join(' ')
}

const handleCommand = (command) => {
  if (command === 'logout') {
    authStore.clearAuth()
    router.push('/login')
  }
}

onMounted(loadPositions)
</script>

<style scoped>
.positions-container { min-height: 100vh; }
.header { display: flex; justify-content: space-between; align-items: center; background: white; border-bottom: 1px solid #e6e6e6; padding: 0 20px; }
.header-left h2 { margin: 0; color: #333; }
.user-dropdown { display: flex; align-items: center; gap: 8px; cursor: pointer; color: #606266; }
.main-content { background: #f0f2f5; padding: 20px; }
.card-header-row { display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; }
.venue-filter, .refresh-controls { display: flex; align-items: center; gap: 12px; }
.filter-label, .last-updated { color: #606266; font-size: 14px; }
.pagination-row { margin-top: 16px; display: flex; justify-content: flex-end; }
.market-cell { font-size: 13px; line-height: 1.5; }
.venue-link { color: #409eff; text-decoration: none; }
.venue-link:hover { text-decoration: underline; }
.primary-label { color: #606266; font-size: 12px; font-weight: 600; }

:deep(.positions-table .related-row td) { background: #fafafa; }
:deep(.positions-table .related-row td:first-child) { padding-left: 18px; }
:deep(.positions-table .group-start-row td) { border-top: 2px solid #dcdfe6; }

@media (max-width: 900px) {
  .refresh-controls { width: 100%; justify-content: space-between; }
}
</style>
