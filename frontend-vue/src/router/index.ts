import { createRouter, createWebHistory } from 'vue-router'

import AgentChatView from '../views/AgentChatView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'agent-chat', component: AgentChatView },
  ],
})
