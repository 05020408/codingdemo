<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { message, request } from "../api";
import type { Course, Page } from "../api";
import { courseTheme, formatDate } from "../coursePresentation";
import AppIcon from "../components/AppIcon.vue";
import CourseActions from "../components/CourseActions.vue";
import CourseForm from "../components/CourseForm.vue";

const route = useRoute(), router = useRouter();
const courses = ref<Course[]>([]), total = ref(0), loading = ref(true);
const error = ref(""), adding = ref(false), notice = ref("");
const searchText = ref("");
const query = computed(() => typeof route.query.q === "string" ? route.query.q.trim() : "");
const page = computed(() => {
  const value = typeof route.query.page === "string" ? Number(route.query.page) : 1;
  return Number.isInteger(value) && value >= 1 && value <= 1000000 ? value : 1;
});
let generation = 0, disposed = false;

async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  try {
    const params = new URLSearchParams({ page: String(page.value), size: "12", q: query.value });
    const data = await request<Page<Course>>(`/api/courses?${params}`);
    if (disposed || current !== generation) return;
    if (page.value > 1 && !data.items.length) {
      await navigate(Math.max(1, Math.ceil(data.total / 12)), true);
      return;
    }
    courses.value = data.items;
    total.value = data.total;
  } catch (cause) {
    if (!disposed && current === generation) error.value = message(cause);
  } finally {
    if (!disposed && current === generation) loading.value = false;
  }
}
function navigate(target: number, replace = false, q = query.value) {
  const location = { path: "/", query: { ...(q ? { q } : {}), ...(target > 1 ? { page: String(target) } : {}) } };
  return replace ? router.replace(location) : router.push(location);
}
async function search() {
  const q = searchText.value.trim();
  if (q === query.value && page.value === 1) await load();
  else await navigate(1, false, q);
}
async function clearSearch() {
  searchText.value = "";
  if (!query.value && page.value === 1) await load();
  else await navigate(1, false, "");
}
async function added() {
  adding.value = false;
  notice.value = "课程已添加，可以上传第一份课件了。";
  if (query.value || page.value !== 1) await navigate(1, false, "");
  else await load();
}
async function updated() {
  notice.value = "课程信息已保存。";
  await load();
}
async function deleted() {
  notice.value = "课程已移除，关联数据正在后台清理。";
  await load();
}
watch([query, page], () => {
  searchText.value = query.value;
  void load();
}, { immediate: true });
onBeforeUnmount(() => { disposed = true; generation++; });
</script>

<template>
  <section class="page-content collection-page">
    <div class="page-heading collection-heading">
      <div>
        <span class="eyebrow">我的学习空间</span>
        <h1>我的课程</h1>
        <p class="muted">从课件到重点，把每一门课的复习整理在一起。</p>
      </div>
      <button class="button primary" @click="adding = true"><AppIcon name="add" />添加课程</button>
    </div>
    <p v-if="notice" role="status" class="notice"><AppIcon name="check" :size="17" />{{ notice }}</p>
    <div class="collection-toolbar">
      <form class="course-search" role="search" @submit.prevent="search">
        <AppIcon name="search" :size="19" />
        <label for="course-search" class="sr-only">搜索课程名称或备注</label>
        <input id="course-search" v-model="searchText" type="search" maxlength="100" placeholder="搜索课程名称或备注" />
        <button v-if="searchText || query" type="button" class="search-clear" aria-label="清除搜索" @click="clearSearch"><AppIcon name="close" :size="16" /></button>
        <button class="search-submit" type="submit">搜索</button>
      </form>
      <span class="collection-sort"><AppIcon name="clock" :size="15" />按最近更新排序</span>
    </div>
    <div class="collection-caption">
      <h2>{{ query ? "搜索结果" : "课程目录" }}<span v-if="!loading && !error" class="count">{{ total }} 门</span></h2>
      <span v-if="query" class="muted">“{{ query }}”</span>
    </div>
    <div v-if="loading" class="course-grid skeleton-grid" role="status" aria-label="正在加载课程">
      <span class="sr-only">正在加载课程目录…</span>
      <div v-for="n in 3" :key="n" class="course-skeleton" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
    </div>
    <div v-else-if="error" class="state-panel">
      <h2>课程目录暂时无法加载</h2>
      <p role="alert" class="error">{{ error }}</p>
      <button class="button secondary" @click="load"><AppIcon name="refresh" :size="17" />重新加载</button>
    </div>
    <div v-else-if="!total && query" class="empty-state search-empty">
      <span class="empty-symbol" aria-hidden="true"><AppIcon name="search" :size="28" /></span>
      <h2>没有找到相关课程</h2>
      <p>试试更短的课程名称，或搜索课程备注中的关键词。</p>
      <button class="button secondary" @click="clearSearch">查看全部课程</button>
    </div>
    <div v-else-if="!total" class="empty-state">
      <div class="empty-book" aria-hidden="true"><span>知笺</span><i></i><i></i><i></i></div>
      <h2>添加你的第一门课程</h2>
      <p>为课程取个名字，再上传课件、整理重点。<br />下次回来，继续从这里开始。</p>
      <button class="button primary" @click="adding = true"><AppIcon name="add" />添加课程</button>
    </div>
    <div v-else class="course-grid">
      <article v-for="course in courses" :key="course.id" class="course-card" :style="courseTheme(course.id)">
        <RouterLink :to="`/courses/${course.id}`" class="course-card-link">
          <div class="card-top"><span class="course-bookmark"><AppIcon name="file" :size="22" /></span><span class="course-kind">课程空间</span></div>
          <h3>{{ course.name }}</h3>
          <p class="course-card-remark">{{ course.remark || "上传课件，开始整理这门课的复习内容。" }}</p>
          <div class="card-footer"><span>{{ formatDate(course.updatedAt) }} 更新</span><strong>进入课程<AppIcon name="arrow" :size="16" /></strong></div>
        </RouterLink>
        <CourseActions class="course-card-manage" :course="course" @saved="updated" @deleted="deleted" />
      </article>
    </div>
    <nav v-if="total > 12 && !error" class="pagination" aria-label="课程分页">
      <button class="button secondary" :disabled="page === 1 || loading" @click="navigate(page - 1)">上一页</button>
      <span>{{ page }} / {{ Math.ceil(total / 12) }}</span>
      <button class="button secondary" :disabled="page * 12 >= total || loading" @click="navigate(page + 1)">下一页</button>
    </nav>
    <CourseForm v-if="adding" @close="adding = false" @saved="added" />
  </section>
</template>
