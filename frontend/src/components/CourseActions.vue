<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { message, request } from "../api";
import type { Course, Job } from "../api";
import AppIcon from "./AppIcon.vue";
import CourseForm from "./CourseForm.vue";

const props = defineProps<{ course: Course; labelled?: boolean }>();
const emit = defineEmits<{ saved: [course: Course]; deleted: [] }>();
const menu = ref<HTMLDetailsElement>();
const trigger = ref<HTMLElement>();
const editing = ref(false);
const dialog = ref<HTMLDialogElement>();
const confirmationInput = ref<HTMLInputElement>();
const confirmation = ref("");
const busy = ref(false);
const error = ref("");
let deleteKey = "";

function closeMenu() {
  if (menu.value) menu.value.open = false;
}
function outside(event: PointerEvent) {
  if (event.target instanceof Node && !menu.value?.contains(event.target)) closeMenu();
}
function escapeMenu() {
  closeMenu();
  trigger.value?.focus();
}
function edit() {
  closeMenu();
  editing.value = true;
}
function closeEditor() {
  editing.value = false;
  void nextTick(() => trigger.value?.focus());
}
function saved(course: Course) {
  closeEditor();
  emit("saved", course);
}
async function askDelete() {
  closeMenu();
  error.value = "";
  confirmation.value = "";
  deleteKey = crypto.randomUUID();
  await nextTick();
  dialog.value?.showModal();
  confirmationInput.value?.focus();
}
function cancelDelete() {
  if (!busy.value) dialog.value?.close();
}
async function remove() {
  if (busy.value || confirmation.value !== props.course.name) return;
  busy.value = true;
  error.value = "";
  try {
    await request<Job>(`/api/courses/${props.course.id}?expectedVersion=${props.course.version}`,
      "DELETE", undefined, deleteKey);
    dialog.value?.close();
    emit("deleted");
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}
onMounted(() => document.addEventListener("pointerdown", outside));
onBeforeUnmount(() => document.removeEventListener("pointerdown", outside));
</script>

<template>
  <div class="course-actions">
    <details ref="menu" class="action-menu" @keydown.esc.stop.prevent="escapeMenu">
      <summary ref="trigger" :class="['course-menu-trigger', { labelled }]"
        :aria-label="`管理课程：${course.name}`" :title="`管理课程：${course.name}`">
        <AppIcon name="more" :size="22" />
        <span v-if="labelled">管理课程</span>
      </summary>
      <div class="action-menu-items">
        <button type="button" @click="edit"><AppIcon name="edit" :size="17" />编辑课程</button>
        <button type="button" class="delete-link" @click="askDelete">
          <AppIcon name="trash" :size="17" />删除课程
        </button>
      </div>
    </details>
    <CourseForm v-if="editing" :course="course" @close="closeEditor" @saved="saved" />
    <dialog ref="dialog" class="dialog" :aria-labelledby="`delete-course-${course.id}`"
      @cancel="busy && $event.preventDefault()" @close="trigger?.focus()">
      <form @submit.prevent="remove">
        <div class="dialog-heading">
          <h2 :id="`delete-course-${course.id}`">删除这门课程？</h2>
          <button type="button" class="icon-button" aria-label="关闭删除窗口" :disabled="busy" @click="cancelDelete">
            <AppIcon name="close" />
          </button>
        </div>
        <p class="muted">“{{ course.name }}”及其关联的课件、重点和答疑记录将被清理，此操作无法撤销。</p>
        <label :for="`confirm-course-${course.id}`">输入课程名称以确认</label>
        <input :id="`confirm-course-${course.id}`" ref="confirmationInput" v-model="confirmation"
          autocomplete="off" :disabled="busy" />
        <p v-if="error" class="error" role="alert">{{ error }} 如课程已被修改，请取消并刷新后重试。</p>
        <div class="dialog-actions">
          <button type="button" class="button secondary" :disabled="busy" @click="cancelDelete">取消</button>
          <button class="button danger" :disabled="busy || confirmation !== course.name">
            {{ busy ? "正在删除…" : "确认删除课程" }}
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>
