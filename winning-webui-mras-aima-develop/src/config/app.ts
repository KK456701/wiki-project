/**
 * 本文件必须仅包含纯常量、不依赖任何浏览器 API 或 Vue 生态
 */

/**
 * 应用全局配置
 * @description 集中管理应用的全局常量，通过 import { APP_TITLE } from '@/config/app' 在任意位置访问
 */
export const APP_TITLE = '指标助手';

/** 应用基础访问路径，与 nginx 反向代理路径及 Vite base 保持一致 */
export const APP_BASE_URL = '/webui-mras-aima/';

/** 后端 API 统一前缀（与 Vite 代理规则保持一致） */
export const API_BASE_PREFIX = '/wiki-agent';
