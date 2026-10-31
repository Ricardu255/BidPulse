import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  // hash 路由：前端单独部署时不需要后端做 SPA fallback
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/overview' },
    {
      path: '/overview',
      component: () => import('@/views/OverviewView.vue'),
      meta: { title: '首页' },
    },
    {
      path: '/playground',
      component: () => import('@/views/PlaygroundView.vue'),
      meta: { title: '模拟竞价' },
    },
    {
      path: '/ads',
      component: () => import('@/views/AdsView.vue'),
      meta: { title: '广告管理' },
    },
    {
      path: '/budget',
      component: () => import('@/views/BudgetView.vue'),
      meta: { title: '预算与频次' },
    },
    {
      path: '/ops',
      component: () => import('@/views/OpsView.vue'),
      meta: { title: '运行监控' },
    },
    {
      path: '/data',
      component: () => import('@/views/DataView.vue'),
      meta: { title: '数据查询' },
    },
    {
      path: '/experiments',
      component: () => import('@/views/ExperimentsView.vue'),
      meta: { title: '演示开关' },
    },
    { path: '/:pathMatch(.*)*', redirect: '/overview' },
  ],
})

export default router
