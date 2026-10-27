<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { useAuth } from "../stores/auth";
import { message, request } from "../api";
const auth = useAuth();
const router = useRouter();
const registering = ref(false),
  busy = ref(false),
  error = ref(""),
  success = ref("");
const username = ref(""),
  password = ref(""),
  displayName = ref("");
function switchMode() {
  registering.value = !registering.value;
  error.value = "";
  success.value = "";
  password.value = "";
}
async function submit() {
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    if (registering.value) {
      await request("/api/auth/register", "POST", {
        username: username.value,
        password: password.value,
        displayName: displayName.value,
      });
      registering.value = false;
      password.value = "";
      success.value = "注册成功，请使用刚才的账号和密码登录。";
    } else {
      await auth.login(username.value, password.value);
      password.value = "";
      await router.replace("/");
    }
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="login-layout">
    <section class="login-story">
      <div class="brand">
        <span class="brand-mark">知</span
        ><span>知笺<small>期末复习工作台</small></span>
      </div>
      <div>
        <span class="eyebrow">A LITTLE ORDER, EVERY DAY</span>
        <h1>把一门课，<br />慢慢读懂。</h1>
        <p>从整理你的课程开始，<br />为接下来的复习留出一个专注的空间。</p>
      </div>
      <span class="story-foot">整理 · 理解 · 巩固</span>
      <div class="paper-decoration" aria-hidden="true">
        <span>课程索引</span><i></i><i></i><i></i><b>01</b>
      </div>
    </section>
    <section class="login-panel">
      <form @submit.prevent="submit" class="login-form">
        <span class="eyebrow">你的学习空间</span>
        <h2>{{ registering ? "创建账号" : "欢迎回来" }}</h2>
        <p class="muted">
          {{
            registering
              ? "课程和复习资料将保存在你的个人账号下。"
              : "登录后，继续整理你的课程。"
          }}
        </p>
        <label for="username">账号</label
        ><input
          id="username"
          v-model="username"
          required
          minlength="3"
          maxlength="32"
          pattern="[A-Za-z0-9_]+"
          autocomplete="username"
          placeholder="3–32 位字母、数字或下划线"
          :disabled="busy"
        />
        <template v-if="registering"
          ><label for="display-name"
            >显示名 <span class="optional">选填</span></label
          ><input
            id="display-name"
            v-model="displayName"
            maxlength="50"
            autocomplete="nickname"
            placeholder="你希望怎样被称呼"
            :disabled="busy"
        /></template>
        <label for="password">密码</label
        ><input
          id="password"
          v-model="password"
          type="password"
          required
          :minlength="registering ? 8 : undefined"
          :maxlength="registering ? 64 : 128"
          :autocomplete="registering ? 'new-password' : 'current-password'"
          placeholder="请输入密码"
          :disabled="busy"
        />
        <small v-if="registering" class="muted"
          >8–64 个字符，UTF-8 编码最多 72 字节。</small
        >
        <p v-if="error || auth.connectionError" class="error" role="alert">
          {{ error || auth.connectionError }}
        </p>
        <p v-if="success" class="success" role="status">{{ success }}</p>
        <button class="button primary login-submit" :disabled="busy">
          {{ busy ? "正在处理…" : registering ? "注册账号" : "进入工作台"
          }}<span aria-hidden="true">→</span>
        </button>
        <p class="switch-auth">
          {{ registering ? "已经有账号？" : "第一次使用？" }}
          <button
            type="button"
            class="text-button"
            :disabled="busy"
            @click="switchMode"
          >
            {{ registering ? "返回登录" : "创建一个账号" }}
          </button>
        </p>
      </form>
      <p class="login-footer">一点一点，拾起学过的知识。</p>
    </section>
  </main>
</template>
