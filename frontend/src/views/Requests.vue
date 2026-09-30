<template>
  <div class="requests-container">
    <el-container>
      <SidebarMenu active-index="9" />

      <el-container>
        <el-header class="header">
          <div class="header-left">
            <h2>Requests</h2>
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
          <el-card>
            <template #header>
              <span>API Request</span>
            </template>

            <el-form label-position="top">
              <el-form-item label="Venue">
                <el-select v-model="venue" placeholder="Select venue" @change="onVenueChange">
                  <el-option label="Kalshi" value="KALSHI" />
                  <el-option label="Polymarket" value="POLYMARKET" />
                  <el-option label="Polymarket — Data" value="POLYMARKET_DATA" />
                </el-select>
              </el-form-item>

              <el-form-item label="HTTP Method">
                <el-select v-model="httpMethod" placeholder="Select method">
                  <el-option label="GET" value="GET" />
                  <el-option label="POST" value="POST" />
                  <el-option label="DELETE" value="DELETE" />
                </el-select>
              </el-form-item>

              <el-form-item v-if="venue === 'POLYMARKET_DATA'" label="Wallet Address (addr)">
                <el-input v-model="addr" placeholder="Wallet address" />
              </el-form-item>

              <el-form-item label="API Endpoint URL">
                <el-input v-model="url" placeholder="Enter API endpoint path">
                  <template #prepend>
                    <span class="base-url">{{ baseUrl }}</span>
                  </template>
                </el-input>
              </el-form-item>

              <el-form-item>
                <el-collapse>
                  <el-collapse-item title="Request Body (JSON)" name="body">
                    <el-input
                      v-model="requestBody"
                      type="textarea"
                      :rows="8"
                      placeholder='{"key": "value"}'
                      class="json-editor body-textarea"
                    />
                  </el-collapse-item>
                </el-collapse>
              </el-form-item>

              <el-form-item>
                <el-button
                  type="primary"
                  :loading="loading"
                  @click="submitRequest"
                >
                  <el-icon v-if="!loading"><Promotion /></el-icon>
                  {{ loading ? 'Sending...' : 'Send Request' }}
                </el-button>
              </el-form-item>
            </el-form>
          </el-card>
          <el-card v-if="response" class="response-card">
            <template #header>
              <div class="response-header">
                <span>Response</span>
                <el-tag
                  :type="responseStatus >= 200 && responseStatus < 300 ? 'success' : 'danger'"
                  size="small"
                >
                  {{ responseStatus }}
                </el-tag>
              </div>
            </template>
            <el-input
              v-model="responseText"
              type="textarea"
              :rows="16"
              readonly
              class="json-output"
            />
          </el-card>
                    <el-card class="help-card">
            <el-collapse>
              <el-collapse-item title="Help — Request Templates" name="help">
                <el-tabs>
                  <el-tab-pane label="Polymarket">
                    <h4>Cancel Order</h4>
                    <el-descriptions :column="1" border size="small">
                      <el-descriptions-item label="Method">DELETE</el-descriptions-item>
                      <el-descriptions-item label="Path">/order</el-descriptions-item>
                      <el-descriptions-item label="Body">
                        <pre class="json-snippet">{
  "orderID": "0x..."
}</pre>
                      </el-descriptions-item>
                    </el-descriptions>

                    <h4>Sell / Place Order</h4>
                    <el-descriptions :column="1" border size="small">
                      <el-descriptions-item label="Method">POST</el-descriptions-item>
                      <el-descriptions-item label="Path">/order</el-descriptions-item>
                      <el-descriptions-item label="Body">
                        <pre class="json-snippet">{
  "deferExec": false,
  "postOnly": false,
  "order": {
    "salt": 123456789,
    "maker": "0xYourWalletAddress",
    "signer": "0xYourWalletAddress",
    "taker": "0x0000000000000000000000000000000000000000",
    "tokenId": "1234567890123456789012345678901234567890123456789012345678901234",
    "makerAmount": "10000000",
    "takerAmount": "5500000",
    "side": "SELL",
    "signatureType": 3,
    "timestamp": "1720000000000",
    "expiration": "0",
    "metadata": "0x0000000000000000000000000000000000000000000000000000000000000000",
    "builder": "0x0000000000000000000000000000000000000000000000000000000000000000",
    "signature": "0xSignatureHexHere"
  },
  "owner": "0xL1ApiKeyHere",
  "orderType": "GTC"
}</pre>
                      </el-descriptions-item>
                    </el-descriptions>
                    <el-alert
                      title="Note: Polymarket orders require EIP-712 signing. For manual testing, use the arbitrage engine or the PolymarketTradingService which handles signing automatically."
                      type="info"
                      :closable="false"
                      style="margin-top: 12px;"
                    />
                  </el-tab-pane>

                  <el-tab-pane label="Kalshi">
                    <h4>Cancel Order</h4>
                    <el-descriptions :column="1" border size="small">
                      <el-descriptions-item label="Method">DELETE</el-descriptions-item>
                      <el-descriptions-item label="Path">/portfolio/events/orders/{orderId}</el-descriptions-item>
                      <el-descriptions-item label="Body">None (empty)</el-descriptions-item>
                    </el-descriptions>

                    <h4>Sell / Place Order</h4>
                    <el-descriptions :column="1" border size="small">
                      <el-descriptions-item label="Method">POST</el-descriptions-item>
                      <el-descriptions-item label="Path">/portfolio/events/orders</el-descriptions-item>
                      <el-descriptions-item label="Body">
                        <pre class="json-snippet">{
  "ticker": "UPCOM-25JUL26-C.USD",
  "side": "ask",
  "count": "10",
  "price": "0.5500",
  "time_in_force": "good_till_canceled",
  "self_trade_prevention_type": "taker_at_cross"
}</pre>
                      </el-descriptions-item>
                    </el-descriptions>
                    <el-alert
                      title="Kalshi notes: side='ask' to sell NO/short, side='bid' to buy YES/long. Price is to 4 decimal places as a string. Count is integer contracts as a string."
                      type="info"
                      :closable="false"
                      style="margin-top: 12px;"
                    />
                  </el-tab-pane>
                </el-tabs>
              </el-collapse-item>
            </el-collapse>
          </el-card>

        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, ArrowDown, Promotion } from '@element-plus/icons-vue'
import SidebarMenu from '../components/SidebarMenu.vue'
import { useAuthStore } from '../stores/auth'
import { proxyRequest, getRequestsConfig } from '../api/requests'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const BASE_URLS = {
  KALSHI: 'https://external-api.kalshi.com/trade-api/v2',
  POLYMARKET: 'https://clob.polymarket.com',
  POLYMARKET_DATA: 'https://data-api.polymarket.com'
}

const venue = ref('KALSHI')
const httpMethod = ref('GET')
const url = ref('')
const addr = ref('')
const requestBody = ref('')
const loading = ref(false)
const response = ref(null)
const responseStatus = ref(0)
const responseText = ref('')

const baseUrl = computed(() => BASE_URLS[venue.value] || '')

function onVenueChange() {
  url.value = ''
}

function handleCommand(command) {
  if (command === 'logout') {
    authStore.clearAuth()
    ElMessage.success('Logged out successfully')
    router.push('/login')
  }
}

async function submitRequest() {
  if (!venue.value) {
    ElMessage.warning('Please select a venue')
    return
  }
  if (!url.value.trim()) {
    ElMessage.warning('Please enter an API endpoint path')
    return
  }

  let body = null
  if (requestBody.value.trim()) {
    try {
      body = JSON.parse(requestBody.value.trim())
    } catch {
      ElMessage.error('Invalid JSON in request body')
      return
    }
  }

  loading.value = true
  response.value = null
  responseStatus.value = 0
  responseText.value = ''

  try {
    let path = url.value.trim()
    if (venue.value === 'POLYMARKET_DATA' && addr.value.trim()) {
      const sep = path.includes('?') ? '&' : '?'
      path += `${sep}addr=${encodeURIComponent(addr.value.trim())}`
    }
    const { data } = await proxyRequest(venue.value, httpMethod.value, path, body)
    response.value = data
    responseStatus.value = data.statusCode

    if (typeof data.body === 'object') {
      responseText.value = JSON.stringify(data.body, null, 2)
    } else if (typeof data.body === 'string') {
      try {
        responseText.value = JSON.stringify(JSON.parse(data.body), null, 2)
      } catch {
        responseText.value = data.body
      }
    } else {
      responseText.value = String(data.body)
    }

    if (data.statusCode >= 200 && data.statusCode < 300) {
      ElMessage.success('Request completed')
    } else {
      ElMessage.warning(`Request returned status ${data.statusCode}`)
    }
  } catch (e) {
    const msg = e.response?.data?.body || e.message || 'Request failed'
    responseText.value = typeof msg === 'object' ? JSON.stringify(msg, null, 2) : String(msg)
    responseStatus.value = e.response?.status || 0
    ElMessage.error('Request failed')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    const { data } = await getRequestsConfig()
    addr.value = data.polymarketWalletAddress || ''
  } catch { /* keep default empty */ }

  const q = route.query
  if (q.venue) {
    venue.value = q.venue.toUpperCase()
    url.value = q.path || ''
    if (q.path) {
      submitRequest()
    }
  }
})
</script>

<style scoped>
.requests-container {
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

.json-editor :deep(textarea),
.json-output :deep(textarea) {
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
}

.body-textarea :deep(textarea) {
  min-width: 80ch;
}

.help-card {
  margin-top: 20px;
}

.help-card h4 {
  margin: 16px 0 8px 0;
  color: #555;
}

.help-card h4:first-child {
  margin-top: 0;
}

.json-snippet {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 12px;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  max-height: 300px;
  overflow-y: auto;
}

.response-card {
  margin-top: 20px;
}

.response-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.base-url {
  color: #909399;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
}
</style>
