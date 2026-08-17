/**
 * 页面状态快照采集
 * @description 采集当前页面的基本状态信息
 */
import type { PageSnapshot } from '../types';

/** 采集当前页面状态快照 */
export function capturePageSnapshot(): PageSnapshot {
  return {
    route: location.pathname + location.search,
    title: document.title,
    userAgent: navigator.userAgent,
    screenResolution: `${window.screen.width}x${window.screen.height}`,
  };
}
