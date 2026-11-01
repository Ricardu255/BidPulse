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
    <section class="login-intro">
      <div>
        <div class="login-brand">
          <span class="brand-mark" aria-hidden="true"></span>
          <div>
            <strong>BidPulse</strong>
            <small>实时竞价 · 投放控制台</small>
          </div>
        </div>

        <div style="margin-top: clamp(42px, 9vh, 96px)">
          <p class="login-scope">DSP · RTB · 投放运营</p>
          <h1>看见每一次<br />出价与消耗</h1>
          <p class="login-intro-copy">
            从竞价请求、预算裁决到 Kafka 账单与 DLT 重放，在一个工作台里核对每一次决策。
          </p>

          <div class="login-flow" aria-label="竞价流程">
            <div class="login-flow-row">
              <span>01</span>
              <div><strong>候选检索</strong><small>地域、年龄、性别与类目命中</small></div>
            </div>
            <div class="login-flow-row">
              <span>02</span>
              <div><strong>预算与频次</strong><small>Redis Lua 原子裁决，不超扣</small></div>
            </div>
            <div class="login-flow-row">
              <span>03</span>
              <div><strong>账单与重放</strong><small>Kafka 幂等消费，异常进 DLT</small></div>
            </div>
          </div>
        </div>
      </div>

      <div class="login-facts" aria-label="系统能力">
        <div class="login-fact">
          <strong>Lua 原子预算</strong>
          <span>并发扣减不超卖</span>
        </div>
        <div class="login-fact">
          <strong>DLT 可重放</strong>
          <span>消费失败可恢复</span>
        </div>
        <div class="login-fact">
          <strong>审计留痕</strong>
          <span>写操作可追溯</span>
        </div>
      </div>
    </section>

    <el-card class="login-card" shadow="never">
      <h2>登录控制台</h2>
      <p class="login-sub">只读、操作员或管理员账号，角色决定可见页面与可执行操作。</p>

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
