/**
 * IndexedDB 数据库 Schema 定义
 * @description 定义数据库结构和版本管理。供 Worker 端使用，因此不导入任何主线程模块。
 */

/** 数据库名称占位 — 运行时由 Worker 注入 */
export const DB_STORE_NAME = 'error_logs';

/** 数据库版本 */
export const DB_VERSION = 1;

/** 创建/升级数据库 */
export function openDatabase(dbName: string): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(dbName, DB_VERSION);

    request.onupgradeneeded = (event) => {
      const db = (event.target as IDBOpenDBRequest).result;

      if (!db.objectStoreNames.contains(DB_STORE_NAME)) {
        const store = db.createObjectStore(DB_STORE_NAME, {
          keyPath: 'id',
          autoIncrement: true,
        });

        // 按错误类型查询
        store.createIndex('by_type', 'type', { unique: false });
        // 按时间范围查询
        store.createIndex('by_timestamp', 'timestamp', { unique: false });
        // 按用户标识查询
        store.createIndex('by_user_id', 'userId', { unique: false });
      }
    };

    request.onsuccess = (event) => {
      resolve((event.target as IDBOpenDBRequest).result);
    };

    request.onerror = (event) => {
      reject((event.target as IDBOpenDBRequest).error);
    };
  });
}
