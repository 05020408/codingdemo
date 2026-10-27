<script setup lang="ts">
import { onMounted, ref } from "vue";
import { message, request } from "../api";
import type { Course, Page } from "../api";
import CourseForm from "../components/CourseForm.vue";
const courses = ref<Course[]>([]),
  total = ref(0),
  page = ref(1),
  loading = ref(true),
  error = ref(""),
  adding = ref(false),
  notice = ref("");
async function load(target = page.value) {
  loading.value = true;
  error.value = "";
  try {
    const data = await request<Page<Course>>(
      `/api/courses?page=${target}&size=12`,
    );
    courses.value = data.items;
    total.value = data.total;
    page.value = target;
  } catch (cause) {
    error.value = message(cause);
  } finally {
    loading.value = false;
  }
}
async function saved() {
  adding.value = false;
  notice.value = "课程已添加，可以开始整理了。";
  await load(1);
}
function date(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "long",
    day: "numeric",
  }).format(new Date(value));
}
onMounted(() => load());
</script>
<template>
  <section class="page-content">
    <div class="page-heading">
      <div>
        <span class="eyebrow">COURSE COLLECTION</span>
        <h1>
          我的课程<span class="count" v-if="!loading && !error">{{
            total
          }}</span>
        </h1>
        <p class="muted">每一门课，都有一个专属的复习空间。</p>
      </div>
      <button class="button primary" @click="adding = true">
        <span aria-hidden="true">＋</span> 添加课程
      </button>
    </div>
    <p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <div class="section-line">
      <h2>课程目录</h2>
      <span>按最近更新排序</span>
    </div>
    <div v-if="loading" role="status" class="state-panel">
      正在打开你的课程目录…
    </div>
    <div v-else-if="error" class="state-panel">
      <p role="alert" class="error">{{ error }}</p>
      <button class="button secondary" @click="load()">重新加载</button>
    </div>
    <div v-else-if="total === 0" class="empty-state">
      <div class="empty-book" aria-hidden="true">
        <span>01</span><i></i><i></i><i></i>
      </div>
      <h2>第一门课，从这里开始</h2>
      <p>添加正在学习的课程，<br />给接下来的复习一个清晰的起点。</p>
      <button class="button primary" @click="adding = true">
        ＋ 添加第一门课程
      </button>
    </div>
    <div v-else class="course-grid">
      <RouterLink
        v-for="(course, index) in courses"
        :key="course.id"
        :to="`/courses/${course.id}`"
        class="course-card"
        :style="{
          '--course-tone': ['#465fbd', '#1b8982', '#b77c47'][index % 3],
        }"
        ><div class="card-top">
          <span class="course-index">{{
            String((page - 1) * 12 + index + 1).padStart(2, "0")
          }}</span
          ><span aria-hidden="true">↗</span>
        </div>
        <h3>{{ course.name }}</h3>
        <p>{{ course.remark || "还没有备注，记录一下这门课的复习安排吧。" }}</p>
        <div class="card-footer">
          <span>{{ date(course.updatedAt) }} 更新</span
          ><strong>进入课程 <span aria-hidden="true">→</span></strong>
        </div></RouterLink
      >
    </div>
    <nav v-if="total > 12" class="pagination" aria-label="课程分页">
      <button
        class="button secondary"
        :disabled="page === 1 || loading"
        @click="load(page - 1)"
      >
        上一页</button
      ><span>{{ page }} / {{ Math.ceil(total / 12) }}</span
      ><button
        class="button secondary"
        :disabled="page * 12 >= total || loading"
        @click="load(page + 1)"
      >
        下一页
      </button>
    </nav>
    <footer class="page-foot">把零散的知识，放回属于它的课程里。</footer>
    <CourseForm v-if="adding" @close="adding = false" @saved="saved" />
  </section>
</template>
