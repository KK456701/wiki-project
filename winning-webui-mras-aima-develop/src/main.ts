import { createApp } from 'vue';
import { createPinia } from 'pinia';
import './style.scss';
import App from './App.vue';
import router from './router';
import vuetify from './plugins/vuetify';
import { monitorSDK } from './monitor';
import { getStorage } from '@/storage/storage';
import { STORAGE_KEYS } from '@/storage/storage-defs';

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);
app.use(router);
app.use(vuetify);

/*
 * MonitorSDK — 全局前端运行时错误监控
 *
 * @description
 * 自动捕获 JS 运行时错误、未处理 Promise 拒绝、资源加载失败、HTTP 请求异常，
 * 日志持久化到 IndexedDB（通过 Web Worker 异步写入，不阻塞主线程）。
 *
 * === 开启/关闭 ===
 *
 * 初始化后默认自动启用（enabled: true）。运行时可随时切换：
 *
 *   // 关闭监控（移除所有监听器、终止 Worker、清除残留资源）
 *   monitorSDK.disable();
 *
 *   // 关闭并清除已存储的日志
 *   monitorSDK.disable(true);
 *
 *   // 重新开启
 *   monitorSDK.enable();
 *
 *   // 查询当前状态
 *   monitorSDK.isEnabled(); // → boolean
 *
 * === 其他常用 API ===
 *
 *   // 手动上报一条错误
 *   monitorSDK.report({ type: 'js_error', message: '...', timestamp: Date.now(), url: location.href });
 *
 *   // 查询错误日志（支持按类型、时间范围过滤）
 *   const logs = await monitorSDK.queryLogs({ types: ['js_error'], startTime: Date.now() - 86400000 });
 *
 *   // 导出所有日志为 JSON
 *   const allLogs = await monitorSDK.exportLogs();
 *
 *   // 清空所有日志
 *   await monitorSDK.clearLogs();
 *
 * === 错误边界 ===
 *
 * 推荐在 App.vue 中使用 <ErrorBoundary> 包裹 <router-view />，
 * 防止单个页面错误导致整个应用白屏：
 *
 *   import ErrorBoundary from '@/monitor/components/ErrorBoundary.vue';
 *
 *   <ErrorBoundary>
 *     <template #default><router-view /></template>
 *   </ErrorBoundary>
 *
 * @see plans/monitor-sdk-architecture.md
 */
monitorSDK.init({
  userId: () => getStorage(STORAGE_KEYS.USER_INFO)?.userId || undefined,
  debug: import.meta.env.DEV,
  mask: {
    enabled: true,
    fields: [],
    globalRules: ['phone', 'idCard', 'email', 'token', 'urlSecretParam', 'ipv4', 'bankCard'],
  },
});

app.mount('#app');
