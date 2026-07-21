import { createRouter, createWebHistory } from 'vue-router'

import AgentChatView from '../views/AgentChatView.vue'
import AgentRunsView from '../views/AgentRunsView.vue'
import MetadataWorkbenchView from '../views/MetadataWorkbenchView.vue'
import TerminologyWorkbenchView from '../views/TerminologyWorkbenchView.vue'
import MonitoringWorkbenchView from '../views/MonitoringWorkbenchView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'agent-chat', component: AgentChatView },
    { path: '/runs', name: 'agent-runs', component: AgentRunsView },
    { path: '/metadata', name: 'metadata-workbench', component: MetadataWorkbenchView },
    { path: '/terminology', name: 'terminology-workbench', component: TerminologyWorkbenchView },
    { path: '/monitoring', name: 'monitoring-workbench', component: MonitoringWorkbenchView },
  ],
})
