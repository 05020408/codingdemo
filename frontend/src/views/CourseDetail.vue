<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { message, request } from "../api";
import type { Course, Job } from "../api";
import CourseForm from "../components/CourseForm.vue";
const route = useRoute(),
  router = useRouter();
const course = ref<Course | null>(null),
  loading = ref(true),
  error = ref(""),
  editing = ref(false),
  deleting = ref(false),
  deleteError = ref(""),
  confirmDialog = ref<HTMLDialogElement>(),
  confirmInput = ref(""),
  notice = ref("");
let deleteKey = "";
async function load() {
  loading.value = true;
  error.value = "";
  course.value = null;
  try {
    course.value = await request<Course>(
      `/api/courses/${encodeURIComponent(String(route.params.id))}`,
    );
    deleteKey = crypto.randomUUID();
  } catch (cause) {
    error.value = message(cause);
  } finally {
    loading.value = false;
  }
}
function saved(value: Course) {
  course.value = value;
  editing.value = false;
  notice.value = "课程信息已保存。";
}
async function showDelete() {
  confirmInput.value = "";
  deleteError.value = "";
  await nextTick();
  confirmDialog.value?.showModal();
}
async function remove() {
  if (!course.value || confirmInput.value !== course.value.name) return;
  deleting.value = true;
  deleteError.value = "";
  try {
    await request<Job>(
      `/api/courses/${course.value.id}?expectedVersion=${course.value.version}`,
      "DELETE",
      undefined,
      deleteKey,
    );
    confirmDialog.value?.close();
    await router.replace("/");
  } catch (cause) {
    deleteError.value = message(cause);
  } finally {
    deleting.value = false;
  }
}
onMounted(load);
watch(() => route.params.id, load);
</script>
<template>
  <section class="page-content">
    <RouterLink class="back-link" to="/">← 返回我的课程</RouterLink>
    <div v-if="loading" role="status" class="state-panel">正在加载课程…</div>
    <div v-else-if="error" class="state-panel">
      <p role="alert" class="error">{{ error }}</p>
      <button class="button secondary" @click="load">重新加载</button>
    </div>
    <template v-else-if="course"
      ><div class="page-heading detail-heading">
        <div>
          <span class="eyebrow">COURSE WORKSPACE</span>
          <h1>{{ course.name }}</h1>
          <p class="course-remark">
            {{ course.remark || "还没有课程备注，可以写下这门课的复习安排。" }}
          </p>
        </div>
        <button class="button secondary" @click="editing = true">
          编辑课程
        </button>
      </div>
      <p v-if="notice" class="notice" role="status">{{ notice }}</p>
      <div class="section-line">
        <h2>课程空间</h2>
        <span>建设进度</span>
      </div>
      <section class="coming-panel">
        <span class="small-label">下一步 · 课件与重点</span>
        <h2>课程已就绪，接下来接入课件。</h2>
        <p>当前版本支持课程管理。课件上传、阅读和重点定位将在后续版本开放。</p>
        <ol class="roadmap">
          <li>
            <span>01</span>
            <div>
              <strong>上传 PPTX</strong>
              <p>保留课件页码，在线阅读课件。</p>
            </div>
          </li>
          <li>
            <span>02</span>
            <div>
              <strong>输入复习重点</strong>
              <p>找到课件中与重点相关的页面。</p>
            </div>
          </li>
          <li>
            <span>03</span>
            <div>
              <strong>围绕内容复习</strong>
              <p>逐步加入 AI 答疑、笔记和习题。</p>
            </div>
          </li>
        </ol>
      </section>
      <section class="danger-section">
        <div>
          <h2>删除课程</h2>
          <p class="muted">删除后课程将立即从列表中移除，随后清理课程数据。</p>
        </div>
        <button class="button danger" @click="showDelete">删除课程</button>
      </section>
      <CourseForm
        v-if="editing"
        :course="course"
        @close="editing = false"
        @saved="saved"
      />
      <dialog
        ref="confirmDialog"
        class="dialog"
        aria-labelledby="delete-title"
        @cancel="deleting && $event.preventDefault()"
      >
        <form @submit.prevent="remove">
          <h2 id="delete-title">确认删除这门课程？</h2>
          <p class="muted">
            此操作无法撤销。请输入课程名称“{{ course.name }}”以确认。
          </p>
          <label for="delete-name">课程名称</label
          ><input
            id="delete-name"
            v-model="confirmInput"
            autocomplete="off"
            :disabled="deleting"
          />
          <p v-if="deleteError" role="alert" class="error">
            {{ deleteError }} 如课程已被修改，请取消并刷新后重试。
          </p>
          <div class="dialog-actions">
            <button
              type="button"
              class="button secondary"
              :disabled="deleting"
              @click="confirmDialog?.close()"
            >
              取消</button
            ><button
              class="button danger"
              :disabled="deleting || confirmInput !== course.name"
            >
              {{ deleting ? "正在删除…" : "确认删除" }}
            </button>
          </div>
        </form>
      </dialog>
    </template>
  </section>
</template>
