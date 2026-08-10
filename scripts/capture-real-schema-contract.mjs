#!/usr/bin/env node
/**
 * 通过 DBHub 的只读工具固化 winex_aima.dbo 允许写入表的结构契约。
 *
 * 该脚本不接收数据库、Schema、表名或 SQL 参数，避免把知识发版工具变成任意查询入口。
 */
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = resolve(
  projectRoot,
  'core-rules-wiki/contracts/winex_aima-dbo-schema.json',
);
const tables = [
  'BUSINESS_UNIT_X_BU_TYPE',
  'CLIBASIC_SURGERY',
  'INPATIENT_ENCOUNTER',
  'INPAT_TRANSFER',
  'INP_CLI_ORDER',
  'INP_SURGICAL_ANESTHESIA_PLAN',
  'MRAS_BUSINESS_ANTI',
  'MRAS_BUSINESS_BLOOD_AUDIT',
  'MRAS_BUSINESS_CONSULTATION',
  'MRAS_BUSINESS_CRITICAL_RPT',
  'MRAS_BUSINESS_DEATH',
  'MRAS_BUSINESS_DIFFI_EMR',
  'MRAS_BUSINESS_DIFFI_EMR_SECOND',
  'MRAS_BUSINESS_FIRSTVISIT',
  'MRAS_BUSINESS_GRADED_CARE',
  'MRAS_BUSINESS_OP_DISC',
  'MRAS_BUSINESS_PATRESCUE',
  'MRAS_BUSINESS_SHIFTHANDOVER',
  'MRAS_BUSINESS_SURGERY',
  'MRAS_BUSINESS_SUR_GRADE',
  'MRAS_BUSINESS_WARDROUND',
  'MRAS_INDEX_SURGREC',
  'MRAS_MEDTECH_PRO',
  'MRAS_MEDTECH_PROC',
  'MRAS_ORGANIZATION',
  'MRAS_PATIENT_EVENT',
  'MRAS_TARGET_DEFINITION',
  'ORGANIZATION',
];

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function parseResponse(raw) {
  const text = raw.trim().startsWith('{')
    ? raw.trim()
    : raw.split(/\r?\n/)
      .filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).trim())
      .join('\n');
  const response = JSON.parse(text);
  if (response.error) throw new Error('DBHub MCP 返回错误。');
  const result = response.result || response;
  const content = result.content?.find(item => item.type === 'text')?.text;
  const nested = content ? JSON.parse(content) : result;
  const rows = nested?.data?.rows || nested?.rows;
  if (!Array.isArray(rows)) throw new Error('DBHub MCP 未返回结构行。');
  return rows;
}

const quoted = tables.map(table => `'${table}'`).join(',');
const sql = `
SELECT DB_NAME() AS database_name,
       s.name AS schema_name,
       t.name AS table_name,
       c.column_id,
       c.name AS column_name,
       ty.name AS data_type,
       c.max_length,
       c.precision,
       c.scale,
       c.is_nullable,
       c.is_identity,
       CASE WHEN dc.object_id IS NULL THEN 0 ELSE 1 END AS has_default
FROM sys.tables t
JOIN sys.schemas s ON s.schema_id=t.schema_id
JOIN sys.columns c ON c.object_id=t.object_id
JOIN sys.types ty ON ty.user_type_id=c.user_type_id
LEFT JOIN sys.default_constraints dc
  ON dc.parent_object_id=c.object_id
 AND dc.parent_column_id=c.column_id
WHERE s.name='dbo' AND t.name IN (${quoted})
ORDER BY t.name,c.column_id`.trim();

const response = await fetch(
  process.env.WIKI_MCP_URL || 'http://127.0.0.1:8765/mcp',
  {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'capture-winex-aima-schema',
      method: 'tools/call',
      params: {
        name: 'execute_sql_winex_aima',
        arguments: { sql },
      },
    }),
  },
);
if (!response.ok) throw new Error(`DBHub MCP HTTP ${response.status}`);
const rows = parseResponse(await response.text());
const grouped = new Map(tables.map(table => [table, []]));
for (const row of rows) {
  if (String(row.database_name).toLowerCase() !== 'winex_aima'
      || String(row.schema_name).toLowerCase() !== 'dbo'
      || !grouped.has(String(row.table_name).toUpperCase())) {
    throw new Error('DBHub 返回了契约范围外的数据库对象。');
  }
  grouped.get(String(row.table_name).toUpperCase()).push({
    ordinal: Number(row.column_id),
    name: String(row.column_name).toUpperCase(),
    type: String(row.data_type).toLowerCase(),
    max_length: Number(row.max_length),
    precision: Number(row.precision),
    scale: Number(row.scale),
    nullable: Boolean(row.is_nullable),
    identity: Boolean(row.is_identity),
    has_default: Boolean(row.has_default),
  });
}
for (const [table, columns] of grouped) {
  if (columns.length === 0) throw new Error(`目标表不存在或不可读取：${table}`);
}
const tableContracts = {};
for (const [table, columns] of grouped) {
  const canonical = JSON.stringify(columns);
  tableContracts[table] = {
    fingerprint_sha256: sha256(canonical),
    columns,
  };
}
const document = {
  schema_version: 'real-database-schema-contract-v1',
  database_name: 'winex_aima',
  schema_name: 'dbo',
  captured_via: 'dbhub-read-only',
  tables: tableContracts,
};
mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, `${JSON.stringify(document, null, 2)}\n`, 'utf8');
console.log(`已固化 ${tables.length} 张表的只读结构契约：${output}`);
