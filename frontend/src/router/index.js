import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import Login from '../views/Login.vue'
import Dashboard from '../views/Dashboard.vue'
import DataFetcher from '../views/DataFetcher.vue'
import SimilarMarkets from '../views/SimilarMarkets.vue'
import Arbitrage from '../views/Arbitrage.vue'
import Orders from '../views/Orders.vue'
import OrderVolume from '../views/OrderVolume.vue'
import OpportunityActivity from '../views/OpportunityActivity.vue'
import OpportunityReport from '../views/OpportunityReport.vue'
import ProfitReport from '../views/ProfitReport.vue'
import Settings from '../views/Settings.vue'
import Requests from '../views/Requests.vue'
import PortfolioPositions from '../views/PortfolioPositions.vue'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/login',
    name: 'Login',
    component: Login,
    meta: { requiresGuest: true }
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: Dashboard,
    meta: { requiresAuth: true }
  },
  {
    path: '/data-fetcher',
    name: 'DataFetcher',
    component: DataFetcher,
    meta: { requiresAuth: true }
  },
  {
    path: '/similar-markets',
    name: 'SimilarMarkets',
    component: SimilarMarkets,
    meta: { requiresAuth: true }
  },
  {
    path: '/arbitrage',
    name: 'Arbitrage',
    component: Arbitrage,
    meta: { requiresAuth: true }
  },
  {
    path: '/settings',
    name: 'Settings',
    component: Settings,
    meta: { requiresAuth: true }
  },
  {
    path: '/requests',
    name: 'Requests',
    component: Requests,
    meta: { requiresAuth: true }
  },
  {
    path: '/portfolio/positions',
    name: 'PortfolioPositions',
    component: PortfolioPositions,
    meta: { requiresAuth: true }
  },
  {
    // Any unknown path funnels through the auth guard (-> /login if not authed)
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  },
  {
    path: '/orders',
    name: 'Orders',
    component: Orders,
    meta: { requiresAuth: true }
  },
  {
    path: '/order-volume',
    name: 'OrderVolume',
    component: OrderVolume,
    meta: { requiresAuth: true }
  },
  {
    path: '/opportunity-activity',
    name: 'OpportunityActivity',
    component: OpportunityActivity,
    meta: { requiresAuth: true }
  },
  {
  path: '/opportunity-report',
  name: 'OpportunityReport',
  component: OpportunityReport,
  meta: { requiresAuth: true } },
  {
  path: '/profit-report',
  name: 'ProfitReport',
  component: ProfitReport,
  meta: { requiresAuth: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  const isAuthenticated = authStore.isAuthenticated()

  if (to.meta.requiresAuth && !isAuthenticated) {
    next('/login')
  } else if (to.meta.requiresGuest && isAuthenticated) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router
