/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** AI Wiki 代理地址 */
  readonly VITE_AI_WIKI_PROXY: string;
  /** Wiki Agent 地址 */
  readonly VITE_WIKI_AGENT_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
