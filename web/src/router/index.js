/**
 * @description 页面路由配置
 */
import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken } from '../utils/session'

const routes = [
    { path: '/', redirect: '/login' },
    { path: '/index.html', redirect: '/login' },
    { path: '/login', component: () => import('../views/auth/LoginView.vue') },
    { path: '/register', component: () => import('../views/auth/RegisterView.vue') },
    { path: '/dashboard', component: () => import('../views/dashboard/IndexView.vue') },
    { path: '/aiops', component: () => import('../views/aiops/AIOpsView.vue') },
    { path: '/chat', redirect: '/dashboard' },
    // 对话页曾经是编辑器的一个「文档」，靠 docId === 'chat-mode' 分叉出来。
    // 留个跳转，旧链接和收藏还能用。
    { path: '/editor/chat-mode', redirect: '/chat' },
    { path: '/editor/:id', component: () => import('../views/editor/EditorView.vue') },
    { path: '/agent-workbench', component: () => import('../views/agent/AgentWorkbench.vue') }
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
    const token = getAccessToken()
    if (to.path !== '/login' && to.path !== '/register' && !token) {
        next('/login')
    } else {
        next()
    }
})

export default router
