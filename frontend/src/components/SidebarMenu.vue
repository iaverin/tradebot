<template>
  <div class="sidebar-spacer"></div>
  <el-aside width="200px" class="sidebar">
    <div class="logo">
      <h3>Trading Desk</h3>
    </div>
    <el-menu
      :default-active="activeIndex"
      class="el-menu-vertical"
      background-color="#304156"
      text-color="#bfcbd9"
      active-text-color="#409EFF"
      router
    >
      <el-menu-item index="1" route="/dashboard">
        <el-icon><HomeFilled /></el-icon>
        <span>Dashboard</span>
      </el-menu-item>

      <el-menu-item index="6" route="/orders">
        <el-icon><List /></el-icon>
        <span>Orders</span>
      </el-menu-item>
      <el-menu-item index="12" route="/portfolio/positions">
        <el-icon><Wallet /></el-icon>
        <span>Open Positions</span>
      </el-menu-item>
      <el-menu-item index="10" route="/order-volume">
        <el-icon><TrendCharts /></el-icon>
        <span>Order Volume</span>
      </el-menu-item>
      <el-menu-item index="11" route="/opportunity-activity">
        <el-icon><TrendCharts /></el-icon>
        <span>Opportunity Activity</span>
      </el-menu-item>
      <el-divider ></el-divider>

      <el-menu-item index="3" route="/similar-markets">
        <el-icon><DataAnalysis /></el-icon>
        <span>Similar Markets</span>
      </el-menu-item>
          <el-divider ></el-divider>
      <el-menu-item index="7" route="/opportunity-report">
        <el-icon><PieChart /></el-icon>
        <span>Report</span>
      </el-menu-item>
      <el-menu-item index="8" route="/profit-report">
        <el-icon><TrendCharts /></el-icon>
        <span>Profit</span>
      </el-menu-item>
      <el-divider ></el-divider>
      <el-menu-item index="4" route="/arbitrage">
        <el-icon><Money /></el-icon>
        <span>Arbitrage</span>
      </el-menu-item>

      <el-menu-item index="2" route="/data-fetcher">
        <el-icon><Download /></el-icon>
        <span>Data Fetcher</span>
      </el-menu-item>

      <el-menu-item index="9" route="/requests">
        <el-icon><Connection /></el-icon>
        <span>Requests</span>
      </el-menu-item>
      <el-divider ></el-divider>
      <el-menu-item index="5" route="/settings">
        <el-icon><Setting /></el-icon>
        <span>Settings</span>
      </el-menu-item>
    </el-menu>
  </el-aside>
</template>

<script setup>
import { HomeFilled, Download, DataAnalysis, Money, List, PieChart, TrendCharts, Setting, Connection, Wallet } from '@element-plus/icons-vue'

defineProps({
  activeIndex: {
    type: String,
    default: '1'
  }
})
</script>

<style scoped>
.sidebar-spacer {
  width: 200px;
  flex-shrink: 0;
}

.sidebar {
  background-color: #304156;
  height: 100vh;
  overflow-y: auto;
  position: fixed;
  left: 0;
  top: 0;
  z-index: 100;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #2a3a4a;
  color: white;
}

.logo h3 {
  margin: 0;
}

.el-menu-vertical {
  border-right: none;
}
.el-divider {
  margin:0px;
  border-top-color: #2a3a4a
}
</style>
