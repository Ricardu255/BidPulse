<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '@/api/auth'
import { errorText } from '@/format'

const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit(): Promise<void> {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    await login(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/overview'
    await router.replace(redirect.startsWith('/') ? redirect : '/overview')
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-brand">
      <span class="brand-mark"></span>
      <div>
        <strong>BidPulse</strong>
        <small>实时竞价 · 控制台</small>
      </div>
    </div>

    <el-card class="login-card" shadow="never">
      <h1>登录控制台</h1>
      <p class="login-sub">使用只读、操作员或管理员账号登录。角色决定可见页面与可执行操作。</p>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input
            v-model="username"
            autocomplete="username"
            placeholder="例如 viewer / operator / admin"
            autofocus
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入密码"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="login-error" />

        <el-button type="primary" native-type="submit" :loading="loading" class="login-submit">
          登录
        </el-button>
      </el-form>

      <div class="login-hint">
        <strong>本地演示</strong>
        <p>密码只从环境变量注入；未配置时后端启动日志会生成一次性随机密码。</p>
        <p>生产请设置 <code>BIDPULSE_AUTH_SECRET</code> 与对应账号的 <code>BIDPULSE_*_PASSWORD</code>。</p>
      </div>
    </el-card>
  </div>
</template>
