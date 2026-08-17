import { NS, STORAGE_DEFS, type StorageKey, type StorageValue } from './storage-defs';

// ============ 内部工具 ============

/** 构建存储 key（按配置决定是否添加命名空间前缀） */
function resolveKey(def: { key: string; namespace?: boolean }): string {
  return def.namespace === false ? def.key : `${NS}:${def.key}`;
}

/** 根据配置获取对应的 Storage 对象 */
function getStore(type: 'localStorage' | 'sessionStorage'): Storage {
  return type === 'localStorage' ? localStorage : sessionStorage;
}

// ============ 公开 API ============

/**
 * 读取存储值
 * @param name 存储项名称（使用 STORAGE_KEYS 常量，如 STORAGE_KEYS.AUTH_TOKEN）
 * @returns 反序列化后的值，异常时返回配置的 defaultValue
 */
export function getStorage<K extends StorageKey>(name: K): StorageValue<K> {
  const def = STORAGE_DEFS[name];
  const store = getStore(def.storage);
  const key = resolveKey(def);
  const shouldSerialize = def.serialize === true;

  try {
    const raw = store.getItem(key);
    if (raw === null) return def.defaultValue;
    return shouldSerialize ? (JSON.parse(raw) as StorageValue<K>) : (raw as StorageValue<K>);
  } catch {
    // eslint-disable-next-line no-console -- 存储读取失败是运行时错误，需要日志记录
    console.warn(`[storage] 读取 "${name}" 失败，返回默认值`);
    return def.defaultValue;
  }
}

/**
 * 写入存储值
 * @param name 存储项名称
 * @param value 要存储的值
 * @returns 是否写入成功
 */
export function setStorage<K extends StorageKey>(name: K, value: StorageValue<K>): boolean {
  const def = STORAGE_DEFS[name];
  const store = getStore(def.storage);
  const key = resolveKey(def);
  const shouldSerialize = def.serialize === true;

  try {
    const raw = shouldSerialize ? JSON.stringify(value ?? null) : String(value);
    store.setItem(key, raw);
    return true;
  } catch (error) {
    // eslint-disable-next-line no-console -- 存储写入失败是运行时错误，需要日志记录
    console.error(`[storage] 写入 "${name}" 失败（可能存储已满）`, error);
    return false;
  }
}

/**
 * 删除存储值
 * @param name 存储项名称
 */
export function removeStorage(name: StorageKey): void {
  const def = STORAGE_DEFS[name];
  const store = getStore(def.storage);
  const key = resolveKey(def);

  try {
    store.removeItem(key);
  } catch {
    // eslint-disable-next-line no-console -- 存储删除失败是运行时错误，需要日志记录
    console.warn(`[storage] 删除 "${name}" 失败`);
  }
}

/**
 * 清除当前命名空间下的所有存储项
 *
 * 注意：清除所有 STORAGE_DEFS 中已注册的 key，
 * 包括有命名空间和无命名空间的。
 */
export function clearStorage(): void {
  for (const name of Object.keys(STORAGE_DEFS) as StorageKey[]) {
    removeStorage(name);
  }
}
