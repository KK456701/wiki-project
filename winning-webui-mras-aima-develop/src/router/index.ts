import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import { APP_BASE_URL, APP_TITLE } from '@/config/app';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/chat',
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', requiresAuth: false },
  },
  {
    path: '/chat/:sessionId?',
    name: 'Chat',
    component: () => import('@/views/ChatView/index.vue'),
    meta: { title: '对话', requiresAuth: false },
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { title: '设置', requiresAuth: false },
  },
  {
    path: '/diagnosis',
    name: 'Diagnosis',
    component: () => import('@/views/DiagnosisWorkspace/index.vue'),
    meta: { title: '排查工作区', requiresAuth: false },
  },
  {
    path: '/trace/:traceId',
    name: 'TraceView',
    component: () => import('@/views/TraceView/index.vue'),
    meta: { title: '链路追踪', requiresAuth: false },
  },
  {
    path: '/monitor',
    name: 'Monitor',
    component: () => import('@/views/MonitorView/index.vue'),
    meta: { title: '前端监控', requiresAuth: false },
  },
];

const router = createRouter({
  history: createWebHistory(APP_BASE_URL),
  routes,
});

/**
 * 全局前置守卫
 * @description 路由跳转前的逻辑处理
 */
router.beforeEach((to, _from, next) => {
  // 设置页面标题
  const title = to.meta.title as string;
  if (title) {
    document.title = `${title} - ${APP_TITLE}`;
  }

  // 可选：强制登录检查（当前未启用）
  // 如需启用，将 requiresAuth 改为 true 并取消下方注释
  /*
  const authStore = useAuthStore();
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    next({ name: 'Login', query: { redirect: to.fullPath } });
    return;
  }
  */

  next();
});

export default router;
