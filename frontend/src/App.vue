<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuth } from "./stores/auth";
import { message } from "./api";
import CleanupStatus from "./components/CleanupStatus.vue";
import AppIcon from "./components/AppIcon.vue";
const auth = useAuth();
const router = useRouter();
const route = useRoute();
const locationLabel = computed(() => route.path.startsWith('/materials/') ? '课件阅读' : route.path.startsWith('/courses/') ? '课程空间' : '我的课程');
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
        ><AppIcon name="courses" /> 我的课程</RouterLink
      >
      <div class="sidebar-note">
        <span class="small-label">你的复习路径</span>
        <p>整理课件<br />找到重点<br />边读边问</p>
        <span class="sidebar-note-foot">学习资料，按课程有序归档。</span>
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
        <nav aria-label="当前位置" class="workspace-breadcrumb">
          <RouterLink v-if="route.path !== '/'" to="/">我的课程</RouterLink>
          <span v-else>学习空间</span>
          <AppIcon name="chevron" :size="13" />
          <span aria-current="page">{{ locationLabel }}</span>
        </nav>
        <span class="version-label">知笺 · 0.4.1</span>
      </header>
      <CleanupStatus />
      <RouterView :key="$route.path" />
    </main>
  </div>
  <RouterView v-else />
</template>
