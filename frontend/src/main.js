import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart } from 'echarts/charts'
import {
  AriaComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent
} from 'echarts/components'
import VChart from 'vue-echarts'

import App from './App.vue'
import router from './router'

use([
  CanvasRenderer,
  BarChart,
  LineChart,
  AriaComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent
])

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.component('VChart', VChart)

app.mount('#app')
