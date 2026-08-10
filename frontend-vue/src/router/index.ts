import { createRouter, createWebHistory } from 'vue-router'

import AgentChatView from '../views/AgentChatView.vue'
import KnowledgeReviewView from '../views/KnowledgeReviewView.vue'
import StandardDiagnosisWorkspace from '../views/StandardDiagnosisWorkspace.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'agent-chat', component: AgentChatView },
    { path: '/knowledge-review', name: 'knowledge-review', component: KnowledgeReviewView },
    { path: '/diagnosis/standard/new', name: 'standard-diagnosis-new', component: StandardDiagnosisWorkspace },
    { path: '/diagnosis/standard/:caseId', name: 'standard-diagnosis', component: StandardDiagnosisWorkspace },
  ],
})
