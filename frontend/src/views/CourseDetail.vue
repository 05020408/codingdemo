<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { message, request } from "../api";
import type { Course } from "../api";
import { courseTheme, formatDate } from "../coursePresentation";
import AppIcon from "../components/AppIcon.vue";
import CourseActions from "../components/CourseActions.vue";
import MaterialList from "../components/MaterialList.vue";
import FocusPanel from "../components/FocusPanel.vue";
import ChatHistory from "../components/ChatHistory.vue";

const route = useRoute(), router = useRouter();
const course = ref<Course | null>(null), loading = ref(true), error = ref(""), notice = ref("");
const tab = computed(() => ["focus", "chat"].includes(String(route.query.tab)) ? String(route.query.tab) : "materials");
let generation = 0, disposed = false;
async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  course.value = null;
  try {
    const value = await request<Course>(`/api/courses/${encodeURIComponent(String(route.params.id))}`);
    if (!disposed && current === generation) course.value = value;
  } catch (cause) {
    if (!disposed && current === generation) error.value = message(cause);
  } finally {
    if (!disposed && current === generation) loading.value = false;
  }
}
function saved(value: Course) {
  course.value = value;
  notice.value = "课程信息已保存。";
}
watch(() => route.params.id, () => { notice.value = ""; void load(); }, { immediate: true });
onBeforeUnmount(() => { disposed = true; generation++; });
</script>

<template>
  <section class="page-content course-detail-page">
    <RouterLink class="back-link" to="/">← 我的课程</RouterLink>
    <div v-if="loading" class="state-panel" role="status">正在加载课程空间…</div>
    <div v-else-if="error" class="state-panel">
      <h2>暂时无法打开这门课程</h2>
      <p role="alert" class="error">{{ error }}</p>
      <button class="button secondary" @click="load"><AppIcon name="refresh" :size="17" />重新加载</button>
    </div>
    <template v-else-if="course">
      <header class="course-overview" :style="courseTheme(course.id)">
        <div class="course-cover-mark" aria-hidden="true">{{ course.name.slice(0, 1) }}</div>
        <div class="course-overview-content">
          <span class="eyebrow">课程空间</span>
          <h1>{{ course.name }}</h1>
          <p class="course-remark">{{ course.remark || "在这里上传课件、整理重点，开始这门课的复习。" }}</p>
          <p class="course-dates"><span>创建于 {{ formatDate(course.createdAt) }}</span><span class="date-separator" aria-hidden="true">·</span><span>最近更新 {{ formatDate(course.updatedAt) }}</span></p>
        </div>
        <CourseActions :course="course" labelled @saved="saved" @deleted="router.replace('/')" />
      </header>
      <p v-if="notice" class="notice" role="status"><AppIcon name="check" :size="17" />{{ notice }}</p>
      <nav class="course-tabs" aria-label="课程功能">
        <RouterLink :to="{ query: { tab: 'materials' } }" :class="{ active: tab === 'materials' }" :aria-current="tab === 'materials' ? 'page' : undefined"><AppIcon name="file" :size="18" />课程课件</RouterLink>
        <RouterLink :to="{ query: { tab: 'focus' } }" :class="{ active: tab === 'focus' }" :aria-current="tab === 'focus' ? 'page' : undefined"><span class="tab-glyph" aria-hidden="true">重</span>复习重点</RouterLink>
        <RouterLink :to="{ query: { tab: 'chat' } }" :class="{ active: tab === 'chat' }" :aria-current="tab === 'chat' ? 'page' : undefined"><span class="tab-glyph" aria-hidden="true">问</span>答疑记录</RouterLink>
      </nav>
      <FocusPanel v-if="tab === 'focus'" :key="course.id" :course-id="course.id" />
      <ChatHistory v-else-if="tab === 'chat'" :course-id="course.id" />
      <MaterialList v-else :course-id="course.id" />
    </template>
  </section>
</template>
