<template>
  <div class="auth-bg">
    <el-card class="auth-card" shadow="hover">
      <div class="logo">◈ Docs</div>
      <h2 class="title">Welcome back</h2>
      <el-form :model="form" label-position="top">
        <el-form-item label="Username">
          <el-input v-model="form.username" placeholder="Enter a username" size="large" prefix-icon="User" />
        </el-form-item>
        <el-form-item label="Password">
          <el-input v-model="form.password" type="password" show-password placeholder="Enter a password" size="large" prefix-icon="Lock" @keyup.enter="handleLogin" />
        </el-form-item>
        <el-button type="primary" class="submit-btn" size="large" @click="handleLogin" :loading="loading">Sign in</el-button>
        <div class="footer-text">
          <router-link to="/register">No account? Sign up</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userApi } from '../../api/user'
import { saveSession } from '../../utils/session'

const router = useRouter()
const loading = ref(false)
const form = ref({ username: '', password: '' })

const handleLogin = async () => {
  if (!form.value.username || !form.value.password) {
    return ElMessage.warning('Enter both username and password')
  }
  loading.value = true
  try {
    const res = await userApi.login(form.value)
    const loginData = res.data || {}
    const accessToken = loginData.accessToken || loginData.token
    const refreshToken = loginData.refreshToken
    const user = loginData.user || {}

    if (!accessToken) {
      throw new Error('Signed in but no accessToken was returned')
    }

    saveSession({ accessToken, refreshToken, user })

    ElMessage.success('Signed in')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-bg { height: 100vh; display: flex; justify-content: center; align-items: center; background: var(--bg-sunken); }
.auth-card { width: 400px; padding: 20px 30px; border-radius: 12px; }
.logo { text-align: center; font-size: 24px; font-weight: 500; color: var(--brand); margin-bottom: 10px; }
.title { text-align: center; font-size: 18px; color: var(--text-primary); margin-bottom: 25px; }
.submit-btn { width: 100%; margin-top: 10px; font-weight: 500;}
.footer-text { text-align: center; margin-top: 15px; font-size: 14px; }
.footer-text a { color: var(--brand); text-decoration: none; }
</style>
