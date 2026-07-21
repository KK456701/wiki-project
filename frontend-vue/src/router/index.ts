import { createRouter, createWebHistory } from 'vue-router'

import AgentChatView from '../views/AgentChatView.vue'
import AgentRunsView from '../views/AgentRunsView.vue'
import MetadataWorkbenchView from '../views/MetadataWorkbenchView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'agent-chat', component: AgentChatView },
    { path: '/runs', name: 'agent-runs', component: AgentRunsView },
    { path: '/metadata', name: 'metadata-workbench', component: MetadataWorkbenchView },
  ],
})
