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
const home = computed(() => route.path === '/');
const reading = computed(() => route.path.startsWith('/materials/'));
const locationLabel = computed(() => route.path.endsWith('/summary') ? '课件核心摘要' : route.path.startsWith('/materials/') ? '课件阅读' : route.path.startsWith('/courses/') ? '课程空间' : '我的课程');
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
  <div v-if="auth.user" class="workspace" :class="{ 'reading-workspace': reading, 'home-workspace': home }">
    <aside v-if="!home" class="sidebar">
      <RouterLink to="/" class="brand" aria-label="知笺首页" title="知笺首页"
        ><span class="brand-mark">知</span
        ><span>知笺</span></RouterLink
      >
      <RouterLink to="/" class="nav-item" aria-label="我的课程" title="我的课程"
        ><AppIcon name="courses" /><span class="nav-item-text">我的课程</span></RouterLink
      >
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
        <template v-if="home">
          <div class="home-navigation">
            <RouterLink to="/" class="brand" aria-label="知笺首页"><span class="brand-mark">知</span><span>知笺</span></RouterLink>
            <nav aria-label="主导航"><RouterLink to="/" aria-current="page"><AppIcon name="courses" :size="17" />我的课程</RouterLink></nav>
          </div>
          <div class="home-account">
            <span class="avatar" aria-hidden="true">{{ auth.user.displayName.slice(0, 1) }}</span>
            <span class="home-account-name" :title="`${auth.user.displayName} (@${auth.user.username})`">{{ auth.user.displayName }}</span>
            <button class="text-button" :disabled="leaving" @click="logout">{{ leaving ? '正在退出…' : '退出' }}</button>
          </div>
        </template>
        <nav v-else aria-label="当前位置" class="workspace-breadcrumb">
          <RouterLink v-if="route.path !== '/'" to="/">我的课程</RouterLink>
          <AppIcon v-if="route.path !== '/'" name="chevron" :size="13" />
          <span aria-current="page">{{ locationLabel }}</span>
        </nav>
        <span v-if="!home" class="version-label">知笺 · 0.4.4</span>
      </header>
      <p v-if="home && logoutError" role="alert" class="error home-logout-error">{{ logoutError }}</p>
      <CleanupStatus />
      <RouterView :key="$route.path" />
    </main>
  </div>
  <RouterView v-else />
</template>
