/**
 * Worker 内 IndexedDB 操作封装
 * @description 在 Web Worker 中运行，不依赖任何主线程模块
 */
import { openDatabase, DB_STORE_NAME } from './schema';
import type { ErrorLogInput, ErrorLog, QueryFilter } from '../types';

/** Worker 内部配置（最小子集） */
interface WorkerConfig {
  dbName: string;
  maxLogCount: number;
  expireDays: number;
}

let config: WorkerConfig | null = null;
let db: IDBDatabase | null = null;

/** 注入 Worker 配置 */
export function setWorkerConfig(cfg: WorkerConfig): void {
  config = cfg;
}

/** 确保数据库已打开 */
async function ensureDb(): Promise<IDBDatabase> {
  if (!db) {
    db = await openDatabase(config!.dbName);
  }
  return db;
}

/** 批量写入日志 */
export async function writeLogs(logs: ErrorLogInput[]): Promise<boolean> {
  try {
    const database = await ensureDb();
    const tx = database.transaction(DB_STORE_NAME, 'readwrite');
    const store = tx.objectStore(DB_STORE_NAME);

    for (const log of logs) {
      store.add(log);
    }

    await new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });

    // 写入后执行垃圾回收
    await garbageCollect(database);
    return true;
  } catch {
    return false;
  }
}

/** 查询日志 */
export async function queryLogs(filter: QueryFilter): Promise<ErrorLog[]> {
  try {
    const database = await ensureDb();
    const tx = database.transaction(DB_STORE_NAME, 'readonly');
    const store = tx.objectStore(DB_STORE_NAME);

    let results: ErrorLog[];

    // 根据查询条件选择索引
    if (filter.startTime !== undefined || filter.endTime !== undefined) {
      const index = store.index('by_timestamp');
      const lower = filter.startTime ?? 0;
      const upper = filter.endTime ?? Date.now();
      const range = IDBKeyRange.bound(lower, upper);
      results = await getAllFromIndex(index, range);
    } else if (filter.types && filter.types.length === 1) {
      const index = store.index('by_type');
      results = await getAllFromIndex(index, IDBKeyRange.only(filter.types[0]));
    } else if (filter.userId) {
      const index = store.index('by_user_id');
      results = await getAllFromIndex(index, IDBKeyRange.only(filter.userId));
    } else {
      results = await getAll(store);
    }

    // 内存中过滤
    if (filter.types && filter.types.length > 1) {
      results = results.filter((log) => filter.types!.includes(log.type));
    }

    // 按时间倒序
    results.sort((a, b) => b.timestamp - a.timestamp);

    // 分页
    const offset = filter.offset ?? 0;
    const limit = filter.limit ?? 100;
    return results.slice(offset, offset + limit);
  } catch {
    return [];
  }
}

/** 导出所有日志 */
export async function exportLogs(): Promise<ErrorLog[]> {
  try {
    const database = await ensureDb();
    const tx = database.transaction(DB_STORE_NAME, 'readonly');
    const store = tx.objectStore(DB_STORE_NAME);
    const results = await getAll(store);
    results.sort((a, b) => b.timestamp - a.timestamp);
    return results;
  } catch {
    return [];
  }
}

/** 清空所有日志 */
export async function clearLogs(): Promise<boolean> {
  try {
    const database = await ensureDb();
    const tx = database.transaction(DB_STORE_NAME, 'readwrite');
    const store = tx.objectStore(DB_STORE_NAME);
    store.clear();
    await new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    return true;
  } catch {
    return false;
  }
}

/** 获取日志数量 */
export async function getLogCount(): Promise<number> {
  try {
    const database = await ensureDb();
    const tx = database.transaction(DB_STORE_NAME, 'readonly');
    const store = tx.objectStore(DB_STORE_NAME);
    return requestToPromise(store.count());
  } catch {
    return 0;
  }
}

/** 垃圾回收：删除过期日志 + FIFO 淘汰 */
async function garbageCollect(database: IDBDatabase): Promise<void> {
  if (!config) return;

  try {
    const tx = database.transaction(DB_STORE_NAME, 'readwrite');
    const store = tx.objectStore(DB_STORE_NAME);
    const index = store.index('by_timestamp');

    // 1. 删除过期日志
    const expireTime = Date.now() - config.expireDays * 24 * 60 * 60 * 1000;
    await deleteByCursor(index, IDBKeyRange.upperBound(expireTime));

    // 2. FIFO 淘汰超出上限的日志
    const count = await requestToPromise(store.count());
    if (count > config.maxLogCount) {
      const deleteCount = count - config.maxLogCount;
      await deleteByCursor(index, null, deleteCount);
    }

    await new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    // 垃圾回收失败不影响主流程
  }
}

/**
 * 通过游标删除记录
 * @param index 索引
 * @param range 键范围（null 表示全部）
 * @param limit 最大删除条数（undefined 表示不限制）
 * @returns 实际删除条数
 */
function deleteByCursor(
  index: IDBIndex,
  range: IDBKeyRange | null,
  limit?: number,
): Promise<number> {
  return new Promise((resolve, reject) => {
    const request = index.openCursor(range);
    let deleted = 0;
    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor && (limit === undefined || deleted < limit)) {
        cursor.delete();
        deleted++;
        cursor.continue();
      } else {
        resolve(deleted);
      }
    };
    request.onerror = () => reject(request.error);
  });
}

/** IDBRequest → Promise */
function requestToPromise<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

/** 获取对象存储中全部数据 */
function getAll(store: IDBObjectStore): Promise<ErrorLog[]> {
  return requestToPromise(store.getAll());
}

/** 从索引中获取全部匹配数据 */
function getAllFromIndex(index: IDBIndex, range?: IDBKeyRange): Promise<ErrorLog[]> {
  return requestToPromise(index.getAll(range));
}
