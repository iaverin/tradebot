<template>
  <el-container class="main-layout">
    <SidebarMenu active-index="3" />
    <el-container direction="vertical">
      <el-header class="header">
        <div class="header-left">
          <h2>Similar Markets</h2>
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
      <el-main>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-card>
              <template #header>Similar Markets Summary</template>
               <div class="status-grid">
                 <div class="status-row">
                   <span class="status-label">Similar Events</span>
                   <span class="status-value">{{ counts?.similar_events_count?.toLocaleString() ?? '-' }}</span>
                 </div>
                 <div class="status-row">
                   <span class="status-label">Similar Markets</span>
                   <span class="status-value">{{ counts?.similar_markets_count?.toLocaleString() ?? '-' }}</span>
                 </div>
                 <div class="status-row">
                   <span class="status-label">Allowed Market Pairs</span>
                   <span class="status-value">{{ counts?.allowed_market_pairs_count?.toLocaleString() ?? '-' }}</span>
                 </div>
               </div>
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card>
              <template #header>Actions</template>
              <div class="button-row">
                <el-button
                  type="primary"
                  :loading="recalculateLoading"
                  @click="recalculate"
                >
                  Recalculate Similar Markets
                </el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <el-row :gutter="20" style="margin-top: 20px;">
          <el-col :span="24">
            <el-card ref="tableCardRef">
              <template #header>
                <div class="card-header-row">
                  <span>Similar Markets</span>
                  <span class="total-label">Total: {{ totalElements.toLocaleString() }}</span>
                </div>
              </template>
              <el-table
                :data="displayRows"
                v-loading="tableLoading"
                border
                size="small"
                style="width: 100%"
                :span-method="spanMethod"
                row-key="rowKey"
                :row-class-name="rowClass"
              >

              <el-table-column label="Markets" min-width="240">
                  <template #default="{ row }">
                    <template v-if="row.__type === 'group'">
                      <div class="event-group-wrapper">
                        <div class="event-group">
                          <div class="event-line">
                            <a
                              :href="polymarketEventUrl(row.polymarket_event_ticker)"
                              target="_blank"
                              rel="noopener noreferrer"
                              class="event-title event-link"
                            >{{ row.polymarket_event_title }}</a>
                            <span class="event-ticker">[{{ row.polymarket_event_ticker }}]</span>
                          </div>
                          <div class="event-divider"></div>
                          <div class="event-line">
                            <a
                              :href="kalshiEventUrl(row.kalshi_event_ticker)"
                              target="_blank"
                              rel="noopener noreferrer"
                              class="event-title event-link"
                            >{{ row.kalshi_event_title }}</a>
                            <span class="event-ticker">[{{ row.kalshi_event_ticker }}]</span>
                          </div>
                        </div>
                        <div class="event-toggle">
                          <el-switch
                            :model-value="eventAllEnabled[groupKey(row)]"
                            :loading="eventToggleLoading[groupKey(row)]"
                            @change="(val) => onEventToggle(row.polymarket_event_ticker, row.kalshi_event_ticker, val)"
                            size="small"
                          />
                        </div>
                      </div>
                    </template>
                    <template v-else>
                      <div class="market-cell">
                        <div class="market-title">{{ row.polymarket_market_title }}</div>
                        <div v-if="row.polymarket_market_close_datetime" class="market-close">
                          {{ formatCloseDate(row.polymarket_market_close_datetime) }}
                        </div>

                        <div class="market-ticker">{{ row.polymarket_market_ticker }}</div>
                      </div>
                    </template>
                  </template>
                </el-table-column>

                <el-table-column label="Kalshi Market" min-width="220">
                  <template #default="{ row }">
                    <div v-if="row.__type !== 'group'" class="market-cell">
                      <div class="market-title">{{ row.kalshi_market_title }}</div>
                      <div v-if="row.kalshi_market_close_datetime" class="market-close">
                        {{ formatCloseDate(row.kalshi_market_close_datetime) }}
                      </div>

                      <div class="market-ticker">{{ row.kalshi_market_ticker }}</div>
                    </div>
                  </template>
                </el-table-column>
                                                <el-table-column label="Similarity" width="100" align="center">
                  <template #default="{ row }">
                    <el-tag
                      v-if="row.__type !== 'group'"
                      :type="similarityTagType(row.similarity)"
                      size="small"
                    >
                      {{ (row.similarity * 100).toFixed(1) }}%
                    </el-tag>
                  </template>
                </el-table-column>

                <el-table-column label="Enabled" width="80" align="center">
                  <template #default="{ row }">
                    <el-switch
                      v-if="row.__type !== 'group'"
                      :model-value="row.enabled"
                      @change="(val) => toggleEnabled(row.id, val)"
                      size="small"
                    />
                  </template>
                </el-table-column>
              </el-table>
              <div class="pagination-row">
                <el-pagination
                  v-model:current-page="currentPage"
                  :page-size="pageSize"
                  :total="totalElements"
                  layout="prev, pager, next, total"
                  background
                  @current-change="loadTable"
                />
              </div>
            </el-card>
          </el-col>
        </el-row>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import SidebarMenu from '../components/SidebarMenu.vue'
import { similarMarketsApi } from '../api/similarMarkets'
import { polymarketEventUrl, kalshiEventUrl } from '../composables/venueUtils'

const router = useRouter()
const authStore = useAuthStore()
const counts = ref(null)
const recalculateLoading = ref(false)
const tableLoading = ref(false)
const tableData = ref([])
const currentPage = ref(1)
const pageSize = ref(100)
const totalElements = ref(0)
const tableCardRef = ref(null)
const eventToggleLoading = ref({})

function groupKey(row) {
  return `${row.polymarket_event_ticker}||${row.kalshi_event_ticker}`
}

const eventAllEnabled = computed(() => {
  const result = {}
  for (const row of tableData.value) {
    const key = groupKey(row)
    if (!(key in result)) result[key] = true
    if (!row.enabled) result[key] = false
  }
  return result
})

function scrollToTable() {
  const el = tableCardRef.value?.$el ?? tableCardRef.value
  if (!el || typeof el.getBoundingClientRect !== 'function') return
  const main = document.querySelector('main.el-main')
  if (main) {
    const mainRect = main.getBoundingClientRect()
    const elRect = el.getBoundingClientRect()
    const target = main.scrollTop + (elRect.top - mainRect.top) - 8
    main.scrollTo({ top: Math.max(0, target), behavior: 'smooth' })
  } else {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

async function loadCounts() {
  try {
    const [similarMarketsRes, allowedPairsRes] = await Promise.all([
      similarMarketsApi.countSimilarMarkets(),
      similarMarketsApi.countAllowedMarketPairs()
    ])

    const similarMarketsData = similarMarketsRes.data
    const allowedPairsData = allowedPairsRes.data

    counts.value = {
      similar_events_count: similarMarketsData?.similar_events_count,
      similar_markets_count: similarMarketsData?.similar_markets_count,
      allowed_market_pairs_count: allowedPairsData?.count
    }
  } catch {
    // keep previous value on transient errors
  }
}

async function loadTable(page) {
  const isPageChange = page !== undefined
  if (isPageChange) currentPage.value = page
  tableLoading.value = true
  try {
    const res = await similarMarketsApi.list(currentPage.value - 1, pageSize.value)
    tableData.value = res.data.content ?? []
    totalElements.value = res.data.totalElements ?? 0
    if (isPageChange) {
      await nextTick()
      scrollToTable()
    }
  } catch {
    ElMessage.error('Failed to load similar markets')
  } finally {
    tableLoading.value = false
  }
}

async function recalculate() {
  recalculateLoading.value = true
  try {
    await similarMarketsApi.recalculate()
    ElMessage.success('Recalculation triggered')
  } catch {
    ElMessage.error('Failed to trigger recalculation')
  } finally {
    recalculateLoading.value = false
  }
}

async function toggleEnabled(id, value) {
  try {
    await similarMarketsApi.toggleEnabled(id)
    ElMessage.success(value ? 'Pair enabled' : 'Pair disabled')
    loadTable()
  } catch {
    ElMessage.error('Failed to toggle')
  }
}

async function onEventToggle(pmEventTicker, ksEventTicker, enabled) {
  const key = groupKey({ polymarket_event_ticker: pmEventTicker, kalshi_event_ticker: ksEventTicker })
  eventToggleLoading.value = { ...eventToggleLoading.value, [key]: true }
  try {
    const { data } = await similarMarketsApi.toggleEventEnabled(pmEventTicker, ksEventTicker, enabled)
    const affectedIds = new Set(data.affectedIds)
    for (const row of tableData.value) {
      if (affectedIds.has(row.id)) {
        row.enabled = enabled
      }
    }
    ElMessage.success(`Trading ${enabled ? 'enabled' : 'disabled'} for event`)
  } catch {
    ElMessage.error('Failed to update event')
  } finally {
    const next = { ...eventToggleLoading.value }
    delete next[key]
    eventToggleLoading.value = next
  }
}

function similarityTagType(similarity) {
  if (similarity >= 0.95) return 'success'
  if (similarity >= 0.85) return 'warning'
  return 'info'
}

function formatCloseDate(iso) {
  if (!iso) return ''
  const close = new Date(iso)
  if (Number.isNaN(close.getTime())) return ''
  const now = new Date()
  const msPerDay = 24 * 60 * 60 * 1000
  // Compare calendar days in local time
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfClose = new Date(close.getFullYear(), close.getMonth(), close.getDate()).getTime()
  const days = Math.round((startOfClose - startOfToday) / msPerDay)
  const dateStr = close.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: '2-digit' })
  const sign = days > 0 ? '+' : ''
  const label = `${sign}${days} day${Math.abs(days) === 1 ? '' : 's'}`
  return `${label} [${dateStr}]`
}

// Inject a synthetic group-header row before each new event pair group
const displayRows = computed(() => {
  const rows = []
  let prevKey = null
  for (const r of tableData.value) {
    const key = `${r.polymarket_event_ticker}||${r.kalshi_event_ticker}`
    if (key !== prevKey) {
      rows.push({
        __type: 'group',
        rowKey: `group-${key}`,
        polymarket_event_title: r.polymarket_event_title,
        polymarket_event_ticker: r.polymarket_event_ticker,
        kalshi_event_title: r.kalshi_event_title,
        kalshi_event_ticker: r.kalshi_event_ticker,
      })
      prevKey = key
    }
    rows.push({ ...r, __type: 'market', rowKey: `market-${r.id}` })
  }
  return rows
})

// Group-header rows span all columns; market rows render normally
function spanMethod({ row, columnIndex }) {
  if (row.__type === 'group') {
    return columnIndex === 0 ? [1, 4] : [0, 0]
  }
  return [1, 1]
}

function handleCommand(command) {
  if (command === 'logout') {
    authStore.clearAuth()
    ElMessage.success('Logged out successfully')
    router.push('/login')
  }
}

function rowClass({ row }) {
  if (row.__type === 'group') return 'event-group-row'
  return row.enabled === false ? 'disabled-row' : ''
}

onMounted(() => {
  loadCounts()
  loadTable()
})
</script>

<style scoped>
.main-layout {
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

.header-right {
  display: flex;
  align-items: center;
}

.user-dropdown {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
}

.status-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
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

.button-row {
  display: flex;
  gap: 12px;
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

.event-group-wrapper {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.event-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 0;
  flex: 1;
  min-width: 0;
}

.event-toggle {
  flex-shrink: 0;
  padding-right: 4px;
}

/* Make the synthetic group-header row sticky so its event titles
   stay visible while scrolling through the markets in that group. */
:deep(tr.event-group-row) {
  background-color: #f5f7fa;
}

:deep(tr.event-group-row > td) {
  position: sticky;
  top: 0;
  z-index: 5;
  background-color: #f5f7fa;
  border-top: 1px solid #dcdfe6;
  border-bottom: 1px solid #dcdfe6;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
}

/* Element Plus wraps the table body in scroll containers that clip sticky.
   Force every ancestor up to el-main (the actual page scroller) to allow
   overflow so sticky positioning resolves against el-main. */
:deep(.el-table__body-wrapper),
:deep(.el-scrollbar),
:deep(.el-scrollbar__wrap),
:deep(.el-scrollbar__view),
:deep(.el-table__inner-wrapper),
:deep(.el-table) {
  overflow: visible !important;
}

/* el-card has overflow:hidden and el-card__body has overflow:auto by default,
   both of which trap position:sticky. Let the page scroller handle scrolling. */
:deep(.el-card),
:deep(.el-card__body) {
  overflow: visible !important;
}

:deep(.el-table__body-wrapper .el-scrollbar__bar) {
  display: none;
}

.event-line {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}

.event-title {
  font-size: 13px;
  color: #303133;
  line-height: 1.4;
}

.event-link {
  color: #409eff;
  text-decoration: none;
}

.event-link:hover {
  text-decoration: underline;
}

.event-ticker {
  font-size: 11px;
  color: #909399;
  font-family: monospace;
}

.event-divider {
  border-top: 1px dashed #dcdfe6;
}

.market-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.market-title {
  font-size: 13px;
  color: #303133;
  line-height: 1.4;
}

.market-ticker {
  font-size: 11px;
  color: #909399;
  font-family: monospace;
}

.market-close {
  font-size: 11px;
  color: #606266;
  margin-top: 2px;
}

.pagination-row {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

:deep(.disabled-row) {
  opacity: 0.45;
  background-color: #f5f5f5;
}
</style>
