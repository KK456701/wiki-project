import { createRouter, createWebHistory } from 'vue-router'

import AgentChatView from '../views/AgentChatView.vue'
import KnowledgeReviewView from '../views/KnowledgeReviewView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'agent-chat', component: AgentChatView },
    { path: '/knowledge-review', name: 'knowledge-review', component: KnowledgeReviewView },
  ],
})
