import type { InjectionKey, Ref } from 'vue'

export interface SqlPreviewContext {
  token: Ref<string>
  ruleId: Ref<string>
  profileId: Ref<string>
  statStart: Ref<string>
  statEnd: Ref<string>
}

export const sqlPreviewContextKey: InjectionKey<SqlPreviewContext> = Symbol('sql-preview-context')
