import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  // hash 路由：前端单独部署时不需要后端做 SPA fallback
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/overview' },
    {
      path: '/overview',
      component: () => import('@/views/OverviewView.vue'),
      meta: { title: '概览' },
    },
    {
      path: '/playground',
      component: () => import('@/views/PlaygroundView.vue'),
      meta: { title: '竞价实验室' },
    },
    {
      path: '/ads',
      component: () => import('@/views/AdsView.vue'),
      meta: { title: '广告与投放' },
    },
    {
      path: '/budget',
      component: () => import('@/views/BudgetView.vue'),
      meta: { title: '预算与频次' },
    },
    {
      path: '/ops',
      component: () => import('@/views/OpsView.vue'),
      meta: { title: '异步运维' },
    },
    { path: '/:pathMatch(.*)*', redirect: '/overview' },
  ],
})

export default router
