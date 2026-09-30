<template>
  <el-container class="main-layout">
    <SidebarMenu active-index="2" />
    <el-container direction="vertical">
      <el-header class="header">
        <div class="header-left">
          <h2>Data Fetcher</h2>
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
              <template #header>Current Fetching Status</template>
              <div class="status-grid">
                <div class="status-row">
                  <span class="status-label">Polymarket fetched</span>
                  <span class="status-value">{{ fetchingStatus?.polymarketFetchedCount?.toLocaleString() ?? '-' }}</span>
                </div>
                <div class="status-row">
                  <span class="status-label">Polymarket requests</span>
                  <span class="status-value">{{ fetchingStatus?.polymarketRequestsCompleted?.toLocaleString() ?? '-' }}</span>
                </div>
                <div class="status-row">
                  <span class="status-label">Kalshi fetched</span>
                  <span class="status-value">{{ fetchingStatus?.kalshiFetchedCount?.toLocaleString() ?? '-' }}</span>
                </div>
                <div class="status-row">
                  <span class="status-label">Kalshi requests</span>
                  <span class="status-value">{{ fetchingStatus?.kalshiRequestsCompleted?.toLocaleString() ?? '-' }}</span>
                </div>
                <div class="status-row">
                  <span class="status-label">Opinion fetched</span>
                  <span class="status-value">{{ fetchingStatus?.opinionFetchedCount?.toLocaleString() ?? '-' }}</span>
                </div>
                <div class="status-row">
                  <span class="status-label">Opinion requests</span>
                  <span class="status-value">{{ fetchingStatus?.opinionRequestsCompleted?.toLocaleString() ?? '-' }}</span>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card>
              <template #header>Next Scheduled Fetch</template>
              <div class="status-content">
                <span v-if="nextCronTime !== null">{{ nextCronTime }}</span>
                <span v-else class="status-loading">Loading...</span>
              </div>
            </el-card>
          </el-col>
        </el-row>
        <el-row :gutter="20" style="margin-top: 20px;">
          <el-col :span="24">
            <el-card>
              <template #header>Manual Fetch Triggers</template>
              <div class="button-row">
                <el-button
                  type="primary"
                  :loading="polymarketLoading"
                  @click="fetchPolymarket"
                >
                  Fetch Polymarket
                </el-button>
                <el-button
                  type="primary"
                  :loading="kalshiLoading"
                  @click="fetchKalshi"
                >
                  Fetch Kalshi
                </el-button>
                <el-button
                  type="primary"
                  :loading="opinionLoading"
                  @click="fetchOpinion"
                >
                  Fetch Opinion
                </el-button>
                <el-button
                  type="primary"
                  :loading="marketsRefreshLoading"
                  @click="marketsRefresh"
                >
                  !!! Fetch and refresh similar_markets
                </el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>
        <el-row :gutter="20" style="margin-top: 20px;">
          <el-col :span="24">
            <el-card>
              <template #header>Fetching Controls</template>
              <div class="button-row">
                <el-button
                  type="warning"
                  :loading="pauseLoading"
                  @click="pauseFetching"
                >
                  Pause
                </el-button>
                <el-button
                  type="success"
                  :loading="resumeLoading"
                  @click="resumeFetching"
                >
                  Resume
                </el-button>
                <el-button
                  type="danger"
                  :loading="stopLoading"
                  @click="stopFetching"
                >
                  Stop
                </el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>
        <el-row :gutter="20" style="margin-top: 20px;">
          <el-col :span="24">
            <el-card>
              <template #header>Data Controls</template>
              <div class="button-row">
                <el-button
                  type="danger"
                  :loading="clearLoading"
                  @click="clearFetchingData"
                >
                  Clear fetching data
                </el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User, ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import SidebarMenu from '../components/SidebarMenu.vue'
import { fetcherApi } from '../api/fetcher'

const router = useRouter()
const authStore = useAuthStore()
const fetchingStatus = ref(null)
const nextCronTime = ref(null)
const polymarketLoading = ref(false)
const kalshiLoading = ref(false)
const opinionLoading = ref(false)
const marketsRefreshLoading = ref(false)
const clearLoading = ref(false)
const pauseLoading = ref(false)
const resumeLoading = ref(false)
const stopLoading = ref(false)

async function loadStatus() {
  try {
    const res = await fetcherApi.getStatus()
    fetchingStatus.value = res.data
  } catch {
    // keep previous value on transient errors
  }
}

async function loadNextCronTime() {
  try {
    const res = await fetcherApi.getNextCronTime()
    nextCronTime.value = res.data?.nextCronTime ?? res.data
  } catch {
    nextCronTime.value = 'Unavailable'
  }
}

async function fetchPolymarket() {
  polymarketLoading.value = true
  try {
    await fetcherApi.triggerPolymarket()
    ElMessage.success('Polymarket fetch triggered')
  } catch {
    ElMessage.error('Failed to trigger Polymarket fetch')
  } finally {
    polymarketLoading.value = false
  }
}

async function fetchKalshi() {
  kalshiLoading.value = true
  try {
    await fetcherApi.triggerKalshi()
    ElMessage.success('Kalshi fetch triggered')
  } catch {
    ElMessage.error('Failed to trigger Kalshi fetch')
  } finally {
    kalshiLoading.value = false
  }
}

async function fetchOpinion() {
  opinionLoading.value = true
  try {
    await fetcherApi.triggerOpinion()
    ElMessage.success('Opinion fetch triggered')
  } catch {
    ElMessage.error('Failed to trigger Opinion fetch')
  } finally {
    opinionLoading.value = false
  }
}

async function marketsRefresh() {
  marketsRefreshLoading.value = true
  try {
    await fetcherApi.triggerMarketsRefresh()
    ElMessage.success('Markets refresh triggered')
  } catch {
    ElMessage.error('Failed to trigger markets refresh')
  } finally {
    marketsRefreshLoading.value = false
  }
}


async function pauseFetching() {
  pauseLoading.value = true
  try {
    await fetcherApi.pause()
    ElMessage.success('Fetching paused')
  } catch {
    ElMessage.error('Failed to pause fetching')
  } finally {
    pauseLoading.value = false
  }
}

async function resumeFetching() {
  resumeLoading.value = true
  try {
    await fetcherApi.resume()
    ElMessage.success('Fetching resumed')
  } catch {
    ElMessage.error('Failed to resume fetching')
  } finally {
    resumeLoading.value = false
  }
}

async function stopFetching() {
  stopLoading.value = true
  try {
    await fetcherApi.stop()
    ElMessage.success('Fetching stopped')
  } catch {
    ElMessage.error('Failed to stop fetching')
  } finally {
    stopLoading.value = false
  }
}

async function clearFetchingData() {
  try {
    await ElMessageBox.confirm(
      'This will permanently delete all fetching data. Continue?',
      'Clear fetching data',
      { confirmButtonText: 'Clear', cancelButtonText: 'Cancel', type: 'warning' }
    )
  } catch {
    return
  }
  clearLoading.value = true
  try {
    await fetcherApi.clearFetchingData()
    ElMessage.success('Fetching data cleared')
  } catch {
    ElMessage.error('Failed to clear fetching data')
  } finally {
    clearLoading.value = false
  }
}

function handleCommand(command) {
  if (command === 'logout') {
    authStore.clearAuth()
    ElMessage.success('Logged out successfully')
    router.push('/login')
  }
}

let statusInterval = null

onMounted(() => {
  loadStatus()
  loadNextCronTime()
  statusInterval = setInterval(loadStatus, 5000)
})

onUnmounted(() => {
  clearInterval(statusInterval)
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

.status-loading {
  color: #909399;
}

.button-row {
  display: flex;
  gap: 12px;
}
</style>
