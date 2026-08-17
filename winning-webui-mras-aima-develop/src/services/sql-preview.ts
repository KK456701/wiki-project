import { request } from '@/utils/request';

export const SQL_DATABASE_ROLE = {
  BUSINESS: 'BUSINESS',
  REAL: 'REAL',
} as const;

export type SqlDatabaseRole = (typeof SQL_DATABASE_ROLE)[keyof typeof SQL_DATABASE_ROLE];

export interface SqlPreviewInput {
  sql: string;
  databaseRole: SqlDatabaseRole;
  ruleId: string;
  profileId?: string | null;
  statStart?: string;
  statEnd?: string;
}

export interface SqlPreviewResult {
  executionId: string;
  databaseRole: SqlDatabaseRole;
  databaseLabel: string;
  status: string;
  rowCount: number;
  truncated: boolean;
  columns: string[];
  rows: Array<Record<string, unknown>>;
  durationMs: number;
  executedSql: string;
}

interface ApiErrorBody {
  detail?: string | { message?: string; code?: string };
  message?: string;
  code?: string;
}

async function readError(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as ApiErrorBody;
    if (typeof body.detail === 'string') return body.detail;
    if (body.detail && typeof body.detail === 'object') {
      return body.detail.message ?? body.detail.code ?? `执行失败 (${response.status})`;
    }
    return body.message ?? body.code ?? `执行失败 (${response.status})`;
  } catch {
    return `执行失败 (${response.status})`;
  }
}

export async function previewSql(input: SqlPreviewInput): Promise<SqlPreviewResult> {
  const response = await request('/sql-executions/preview', {
    method: 'POST',
    body: JSON.stringify(input),
    timeout: 35_000,
  });
  if (!response.ok) throw new Error(await readError(response));
  return (await response.json()) as SqlPreviewResult;
}

export function inferSqlDatabaseRole(sql: string, hint?: string | null): SqlDatabaseRole | null {
  const normalizedHint = hint?.trim().toUpperCase();
  if (normalizedHint === SQL_DATABASE_ROLE.BUSINESS || normalizedHint === 'ORACLE') {
    return SQL_DATABASE_ROLE.BUSINESS;
  }
  if (normalizedHint === SQL_DATABASE_ROLE.REAL || normalizedHint === 'SQLSERVER') {
    return SQL_DATABASE_ROLE.REAL;
  }
  if (/\bMRAS_[A-Z0-9_]+\b/i.test(sql)) return SQL_DATABASE_ROLE.REAL;
  if (
    /\b(?:SOURCE|SOURCE_EXTRACT|SOURCE_EXTRACT_SQL|EVENT|EXTENDED_EVENT_SQL)\b/i.test(
      normalizedHint ?? '',
    )
  ) {
    return SQL_DATABASE_ROLE.BUSINESS;
  }
  if (
    /\b(?:OVERVIEW|OVERVIEW_SQL|DEPARTMENT|DEPARTMENT_SQL|PATIENT|PATIENT_SQL)\b/i.test(
      normalizedHint ?? '',
    )
  ) {
    return SQL_DATABASE_ROLE.REAL;
  }
  if (normalizedHint === 'SYNC') return SQL_DATABASE_ROLE.BUSINESS;
  return null;
}
