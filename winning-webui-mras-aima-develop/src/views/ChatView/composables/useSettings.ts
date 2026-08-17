import { ref, reactive } from 'vue';
import type {
  ConnectionTestResult,
  RuntimeConnectionSaveInput,
  RuntimeDatabaseSetting,
  RuntimeModelConfigInput,
  RuntimeModelSetting,
  RuntimeSettings,
} from '@/types/chat';
import {
  getRuntimeSettings,
  saveRuntimeConnection,
  saveRuntimeModelConfiguration,
  setRuntimeDefaultModel,
  testRuntimeConnection,
} from '@/services/chat';
import { useChatStore } from '@/stores/chat';

export function useSettings() {
  const chatStore = useChatStore();
  const activeTab = ref<'models' | 'databases'>('models');
  const settings = ref<RuntimeSettings | null>(null);
  const loading = ref(true);
  const error = ref('');
  const testing = ref('');
  const saving = ref('');
  const message = ref('');
  const connectionResults = reactive<Record<string, ConnectionTestResult>>({});
  const connectionDrafts = reactive<Record<string, RuntimeConnectionSaveInput>>({});
  const modelDrafts = reactive<Record<string, RuntimeModelConfigInput>>({});

  async function load() {
    loading.value = true;
    error.value = '';
    try {
      settings.value = await getRuntimeSettings();
      for (const item of settings.value.models) {
        modelDrafts[item.id] = modelDraft(item);
      }
      for (const item of settings.value.databases) {
        connectionDrafts[item.id] = connectionDraft(item);
      }
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : '运行配置读取失败。';
    } finally {
      loading.value = false;
    }
  }

  function modelDraft(item: RuntimeModelSetting): RuntimeModelConfigInput {
    return {
      id: item.id,
      name: item.name,
      provider: item.provider as RuntimeModelConfigInput['provider'],
      model: item.model || '',
      baseUrl: item.baseUrl || '',
      completionsPath: item.completionsPath || '',
      apiKey: '',
      thinking: Boolean(item.thinking),
      enableThinking: item.enableThinking,
    };
  }

  function connectionDraft(item: RuntimeDatabaseSetting): RuntimeConnectionSaveInput {
    return {
      enabled: item.enabled,
      driverClassName:
        item.engine === 'Oracle'
          ? 'oracle.jdbc.OracleDriver'
          : 'com.microsoft.sqlserver.jdbc.SQLServerDriver',
      url: item.endpoint === '未配置' ? '' : item.endpoint,
      username: item.username,
      password: '',
      schema: item.schema,
      maximumPoolSize: Number(item.pool.maximumPoolSize || 2),
      minimumIdle: Number(item.pool.minimumIdle || 0),
      connectionTimeoutMs: Number(item.pool.connectionTimeoutMs || 30_000),
      validationQuery: item.engine === 'Oracle' ? 'select 1 from dual' : 'SELECT 1',
    };
  }

  function defaultDriver(item: RuntimeDatabaseSetting) {
    return item.engine === 'Oracle'
      ? 'oracle.jdbc.OracleDriver'
      : 'com.microsoft.sqlserver.jdbc.SQLServerDriver';
  }

  async function handleTestConnection(item: RuntimeDatabaseSetting) {
    testing.value = item.id;
    try {
      const draft = connectionDrafts[item.id];
      const changed =
        Boolean(draft) &&
        (draft.driverClassName !== defaultDriver(item) ||
          draft.url !== (item.endpoint === '未配置' ? '' : item.endpoint) ||
          draft.username !== item.username ||
          draft.schema !== item.schema ||
          Boolean(draft.password));
      const testInput = changed ? draft : undefined;
      connectionResults[item.id] = await testRuntimeConnection(item.id, testInput);
    } catch (reason) {
      connectionResults[item.id] = {
        connectionId: item.id,
        status: 'FAILED',
        message: reason instanceof Error ? reason.message : '连接测试失败。',
        durationMs: 0,
      };
    } finally {
      testing.value = '';
    }
  }

  async function handleSaveDatabase(item: RuntimeDatabaseSetting) {
    saving.value = `database:${item.id}`;
    message.value = '';
    try {
      const result = await saveRuntimeConnection(item.id, connectionDrafts[item.id]);
      message.value = result.message;
      await load();
    } catch (reason) {
      message.value = reason instanceof Error ? reason.message : '数据库配置保存失败。';
    } finally {
      saving.value = '';
    }
  }

  async function handleSaveModels() {
    if (!settings.value) return;
    saving.value = 'models';
    message.value = '';
    try {
      const result = await saveRuntimeModelConfiguration({
        defaultModel: settings.value.defaultModel,
        models: settings.value.models.map((item) => modelDrafts[item.id]),
      });
      settings.value.defaultModel = result.defaultModel;
      settings.value.models = result.models;
      for (const item of result.models) {
        modelDrafts[item.id] = modelDraft(item);
      }
      chatStore.switchModel(result.defaultModel);
      message.value = result.message;
    } catch (reason) {
      message.value = reason instanceof Error ? reason.message : '模型配置保存失败。';
    } finally {
      saving.value = '';
    }
  }

  async function handleSetDefaultModel(modelId: string) {
    saving.value = `default:${modelId}`;
    message.value = '';
    try {
      const result = await setRuntimeDefaultModel(modelId);
      if (settings.value) settings.value.defaultModel = result.defaultModel;
      chatStore.switchModel(modelId);
      message.value = result.message;
    } catch (reason) {
      message.value = reason instanceof Error ? reason.message : '默认模型切换失败。';
    } finally {
      saving.value = '';
    }
  }

  return {
    activeTab,
    settings,
    loading,
    error,
    testing,
    saving,
    message,
    connectionResults,
    connectionDrafts,
    modelDrafts,
    load,
    chatStore,
    handleTestConnection,
    handleSaveDatabase,
    handleSaveModels,
    handleSetDefaultModel,
  };
}
