<template>
  <div class="auth-bg">
    <el-card class="auth-card" shadow="hover">
      <div class="logo">◈ Docs</div>
      <h2 class="title">Create an account</h2>

      <el-form :model="form" :rules="rules" ref="formRef" label-position="top">

        <el-form-item label="Username" prop="username">
          <el-input v-model="form.username" placeholder="Enter a username" size="large" />
        </el-form-item>

        <el-form-item label="Email" prop="email">
          <el-input v-model="form.email" placeholder="e.g. you@example.com" size="large" />
        </el-form-item>

        <el-form-item label="Phone" prop="phone">
          <el-input v-model="form.phone" placeholder="11-digit phone number" size="large" />
        </el-form-item>

        <el-form-item label="Password" prop="password">
          <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="Upper and lowercase, a number and a symbol"
              size="large"
              @input="checkPasswordStrength"
          />

          <!-- 实时校验清单面板 -->
          <div class="password-checklist">
            <div :class="['check-item', strength.length ? 'met' : '']">
              <el-icon><CircleCheck v-if="strength.length" /><CircleClose v-else /></el-icon>
              At least 8 characters
            </div>
            <div :class="['check-item', strength.upper ? 'met' : '']">
              <el-icon><CircleCheck v-if="strength.upper" /><CircleClose v-else /></el-icon>
              An uppercase letter
            </div>
            <div :class="['check-item', strength.lower ? 'met' : '']">
              <el-icon><CircleCheck v-if="strength.lower" /><CircleClose v-else /></el-icon>
              A lowercase letter
            </div>
            <div :class="['check-item', strength.mixed ? 'met' : '']">
              <el-icon><CircleCheck v-if="strength.mixed" /><CircleClose v-else /></el-icon>
              A number and a symbol (@$!%*?&)
            </div>
          </div>
        </el-form-item>

        <el-button
            type="primary"
            class="submit-btn"
            size="large"
            @click="handleRegister"
            :loading="loading"
            :disabled="!isPasswordValid"
        >
          Sign up
        </el-button>

        <div class="footer-text">
          <router-link to="/login">Already have an account? Sign in</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { REGEX } from '../../constants'
import { userApi } from '../../api/user'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)

const form = ref({ username: '', email: '', phone: '', password: '' })

// Password强度实时状态
const strength = reactive({
  length: false,
  upper: false,
  lower: false,
  mixed: false
})

// 实时检查函数
const checkPasswordStrength = () => {
  const pwd = form.value.password
  strength.length = pwd.length >= 8
  strength.upper = REGEX.HAS_UPPER.test(pwd)
  strength.lower = REGEX.HAS_LOWER.test(pwd)
  strength.mixed = REGEX.HAS_NUMBER.test(pwd) && REGEX.HAS_SPECIAL.test(pwd)
}

// 计算属性：Password是否完全合格
const isPasswordValid = computed(() => {
  return strength.length && strength.upper && strength.lower && strength.mixed
})

const rules = {
  username: [{ required: true, message: 'Username is required', trigger: 'blur' }],
  email: [{ required: true, message: 'Email or phone', trigger: 'blur' }]
  // Password校验逻辑现在通过 strength 面板和 isPasswordValid 按钮禁用态来控制，更直观
}

const handleRegister = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await userApi.register(form.value)
      ElMessage.success('Account created — please sign in')
      router.push('/login')
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.auth-bg { height: 100vh; display: flex; justify-content: center; align-items: center; background: var(--bg-sunken); }
.auth-card { width: 420px; padding: 20px 30px; border-radius: 12px; }
.logo { text-align: center; font-size: 24px; font-weight: 500; color: var(--brand); margin-bottom: 10px; }
.title { text-align: center; font-size: 16px; color: var(--text-secondary); margin-bottom: 30px; }

/* Password清单样式 */
.password-checklist {
  margin-top: 12px;
  background: var(--bg-sunken);
  padding: 12px;
  border-radius: 8px;
}
.check-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-muted); /* 默认灰色：未达成 */
  margin-bottom: 4px;
  transition: all 0.3s;
}
.check-item.met {
  color: var(--success); /* 达成后变绿色 */
  font-weight: 500;
}
.check-item .el-icon { font-size: 14px; }

.submit-btn { width: 100%; margin-top: 20px; }
.footer-text { text-align: center; margin-top: 20px; font-size: 14px; }
</style>