<template>
  <div class="settings-container">
    <el-container>
      <SidebarMenu active-index="5" />

      <el-container>
        <el-header class="header">
          <div class="header-left">
            <h2>Settings</h2>
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
          <el-row :gutter="20">
            <el-col :span="12">
              <el-card>
                <template #header>
                  <span>Trading Settings</span>
                </template>

                <div class="setting-row">
                  <div class="setting-label">
                    <span>Errors Count</span>
                    <p class="setting-description">
                      Errors count from last enabled.
                    </p>
                  </div>
                  <div class="setting-control">
                    <span class="setting-value">{{ errorsCount }}</span>
                  </div>
                </div>
                <el-divider />

                <div class="setting-row">
                  <div class="setting-label">
                    <span>Trading Enabled</span>
                    <p class="setting-description">
                      When enabled, arbitrage opportunities will trigger real order placement.
                      When disabled, opportunities are detected and logged only.
                    </p>
                  </div>
                  <div class="setting-control">
                    <el-switch
                      v-model="tradingEnabled"
                      :loading="loading"
                      @change="onToggle"
                      size="large"
                    />
                  </div>
                </div>
                <el-divider />
                <div class="setting-row">
                  <div class="setting-label">
                    <span>Maximum Order Value in USD</span>
                    <p class="setting-description">
                      Maximum allowed cost for a single arbitrage order.
                    </p>
                  </div>
                  <div class="setting-control">
                    <span class="setting-value">${{ maxOrderCost }}</span>
                  </div>
                </div>
                <el-divider />
                <div class="setting-row">
                  <div class="setting-label">
                    <span>Minimum Order Shares</span>
                    <p class="setting-description">
                      Minimum number of shares required per order (Polymarket default min_order_size).
                    </p>
                  </div>
                  <div class="setting-control">
                    <span class="setting-value">{{ minimumOrderShares }} shares</span>
                  </div>
                </div>
                <el-divider />
                <div class="setting-row">
                  <div class="setting-label">
                    <span>Active Pairs Limit per Market Pair</span>
                    <p class="setting-description">
                      Maximum unfinished arbitrage order pairs allowed for the same Polymarket/Kalshi markets.
                    </p>
                  </div>
                  <div class="setting-control active-pairs-control">
                    <el-input-number
                      v-model="activePairsLimit"
                      :min="1"
                      :precision="0"
                      :disabled="activePairsSaving"
                      size="small"
                    />
                    <el-button
                      type="primary"
                      size="small"
                      :loading="activePairsSaving"
                      @click="saveActivePairsLimit"
                    >
                      Save
                    </el-button>
                  </div>
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
import SidebarMenu from '../components/SidebarMenu.vue'
import { useAuthStore } from '../stores/auth'
import { getSettingsInfo, getTradingEnabled, setActivePairsLimit, setTradingEnabled } from '../api/settings'

const router = useRouter()
const authStore = useAuthStore()

const tradingEnabled = ref(false)
const maxOrderCost = ref(0)
const errorsCount = ref(0)
const minimumOrderShares = ref(0)
const activePairsLimit = ref(1)
const confirmedActivePairsLimit = ref(1)
const activePairsSaving = ref(false)
const loading = ref(false)

async function loadSettingsInfo() {
  try {
    const { data } = await getSettingsInfo()
    maxOrderCost.value = data.maxOrderCost
    minimumOrderShares.value = data.minimumOrderShares
    errorsCount.value = data.errorsCount
    activePairsLimit.value = data.activePairsLimit
    confirmedActivePairsLimit.value = data.activePairsLimit

  } catch (e) {
    ElMessage.error('Failed to load settings info')
  }
}

async function saveActivePairsLimit() {
  activePairsSaving.value = true
  try {
    const { data } = await setActivePairsLimit(activePairsLimit.value)
    activePairsLimit.value = data.activePairsLimit
    confirmedActivePairsLimit.value = data.activePairsLimit
    ElMessage.success('Active pairs limit updated')
  } catch (e) {
    activePairsLimit.value = confirmedActivePairsLimit.value
    ElMessage.error('Failed to update active pairs limit')
  } finally {
    activePairsSaving.value = false
  }
}

async function loadTradingEnabled() {
  try {
    const { data } = await getTradingEnabled()
    tradingEnabled.value = data.tradingEnabled
  } catch (e) {
    ElMessage.error('Failed to load trading setting')
  }
}

async function onToggle(value) {
  loading.value = true
  try {
    await setTradingEnabled(value)
    ElMessage.success(`Trading ${value ? 'enabled' : 'disabled'}`)
  } catch (e) {
    ElMessage.error('Failed to update trading setting')
    tradingEnabled.value = !value // revert
  } finally {
    loading.value = false
  }
}

function handleCommand(command) {
  if (command === 'logout') {
    authStore.clearAuth()
    ElMessage.success('Logged out successfully')
    router.push('/login')
  }
}

onMounted(() => {
  loadTradingEnabled()
  loadSettingsInfo()
})
</script>

<style scoped>
.settings-container {
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
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.main-content {
  background: #f0f2f5;
  padding: 20px;
}

.setting-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.setting-label {
  flex: 1;
}

.setting-label span {
  font-size: 15px;
  font-weight: 500;
}

.setting-description {
  color: #909399;
  font-size: 13px;
  margin: 4px 0 0 0;
}

.setting-control {
  padding-top: 4px;
}

.setting-value {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.active-pairs-control {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
