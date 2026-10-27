<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { useAuth } from "./stores/auth";
import { message } from "./api";
import CleanupStatus from "./components/CleanupStatus.vue";
const auth = useAuth();
const router = useRouter();
const logoutError = ref("");
const leaving = ref(false);
async function logout() {
  leaving.value = true;
  logoutError.value = "";
  try {
    await auth.logout();
    await router.replace("/login");
  } catch (error) {
    logoutError.value = message(error);
  } finally {
    leaving.value = false;
  }
}
</script>
<template>
  <div v-if="auth.user" class="workspace">
    <aside class="sidebar">
      <RouterLink to="/" class="brand"
        ><span class="brand-mark">知</span
        ><span>知笺<small>期末复习工作台</small></span></RouterLink
      >
      <p class="nav-caption">我的学习空间</p>
      <RouterLink to="/" class="nav-item"
        ><span aria-hidden="true">▦</span> 我的课程</RouterLink
      >
      <div class="sidebar-note">
        <span class="small-label">从一门课开始</span>
        <p>把课程整理好，<br />让复习有迹可循。</p>
        <span class="notebook-lines" aria-hidden="true"></span>
      </div>
      <div class="account">
        <span class="avatar">{{ auth.user.displayName.slice(0, 1) }}</span>
        <div>
          <strong>{{ auth.user.displayName }}</strong
          ><small>@{{ auth.user.username }}</small>
        </div>
        <button class="text-button" :disabled="leaving" @click="logout">
          退出
        </button>
      </div>
      <p v-if="logoutError" role="alert" class="error">{{ logoutError }}</p>
    </aside>
    <main class="main">
      <header class="topbar">
        <span>学习空间 <span class="breadcrumb">/</span> 课程</span
        ><span class="version-label">重点定位 · 0.3</span>
      </header>
      <CleanupStatus />
      <RouterView :key="$route.path" />
    </main>
  </div>
  <RouterView v-else />
</template>
