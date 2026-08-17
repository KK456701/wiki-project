import type { UserInfo } from '@/types/user';

// ============ 命名空间前缀 ============

/** 全局命名空间前缀，所有 key 自动添加此前缀（namespace 未禁用时） */
export const NS = 'mrasAima';

// ============ 存储类型 ============

export type StorageType = 'localStorage' | 'sessionStorage';

// ============ 存储项定义 ============

export interface StorageDef<T = unknown> {
  /** 底层存储 key（不含前缀） */
  key: string;
  /** 存储后端 */
  storage: StorageType;
  /** 默认值（读取不存在时返回） */
  defaultValue: T;
  /**
   * 是否添加命名空间前缀，默认 true
   * - true：实际读写 key 为 `mrasAima:xxx`
   * - false：直接使用原始 key（适用于与其他系统共享的存储项）
   */
  namespace?: boolean;
  /**
   * 是否进行 JSON 序列化/反序列化，默认 false
   * - true：自动 JSON.stringify / JSON.parse（适用于对象、数组等结构化数据）
   * - false：原样读写字符串（默认，兼容其他系统写入的纯文本值）
   */
  serialize?: boolean;
}

// ============ ★ 所有存储项集中定义 ★ ============

export const STORAGE_DEFS = {
  /** 用户认证 Token: Bearer eyJhb... */
  AUTH_TOKEN: {
    key: 'Authorization',
    storage: 'sessionStorage',
    defaultValue: '',
    namespace: false, // 来自 60 统一登录
  } as StorageDef<string>,

  /** 当前用户信息 */
  USER_INFO: {
    key: 'userInfo',
    storage: 'sessionStorage',
    defaultValue: null,
    namespace: false, // 来自 60 统一登录
    serialize: true,
  } as StorageDef<UserInfo | null>,
} as const;

// ============ 存储键常量（调用方使用，避免魔法字符串） ============

/**
 * 存储键名称常量（自动从 STORAGE_DEFS 派生，避免手动维护）
 *
 * 使用方式：
 *   import { STORAGE_KEYS } from '@/storage/storage-defs';
 *   getStorage(STORAGE_KEYS.AUTH_TOKEN)     // ✅ 禁止 getStorage('AUTH_TOKEN')
 */
export const STORAGE_KEYS: { [K in keyof typeof STORAGE_DEFS]: K } = Object.keys(
  STORAGE_DEFS,
).reduce((acc, key) => ({ ...acc, [key]: key }), {} as { [K in keyof typeof STORAGE_DEFS]: K });

// ============ 类型工具：从配置推导值类型 ============

/** 所有存储 key 名称的联合类型 */
export type StorageKey = keyof typeof STORAGE_DEFS;

/** 根据 key 名称推导对应的值类型 */
export type StorageValue<K extends StorageKey> = (typeof STORAGE_DEFS)[K]['defaultValue'];
