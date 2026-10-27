<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { ApiError, message, request } from "../api";
import type { Course } from "../api";
const props = defineProps<{ course?: Course }>();
const emit = defineEmits<{ close: []; saved: [course: Course] }>();
const dialog = ref<HTMLDialogElement>(),
  nameInput = ref<HTMLInputElement>();
const name = ref(props.course?.name ?? ""),
  remark = ref(props.course?.remark ?? ""),
  busy = ref(false),
  error = ref(""),
  stale = ref(false);
// A retry after a lost response reuses the same key. Editing the payload starts a new operation.
let pendingKey = "",
  pendingBody = "";
const previousFocus = document.activeElement as HTMLElement | null;
onMounted(async () => {
  dialog.value?.showModal();
  await nextTick();
  nameInput.value?.focus();
});
onBeforeUnmount(() => previousFocus?.focus());
function close() {
  if (!busy.value) emit("close");
}
async function save() {
  busy.value = true;
  error.value = "";
  const body = props.course
    ? {
        name: name.value,
        remark: remark.value,
        expectedVersion: props.course.version,
      }
    : { name: name.value, remark: remark.value };
  const serialized = JSON.stringify(body);
  if (serialized !== pendingBody) {
    pendingKey = crypto.randomUUID();
    pendingBody = serialized;
  }
  try {
    const course = await request<Course>(
      props.course ? `/api/courses/${props.course.id}` : "/api/courses",
      props.course ? "PATCH" : "POST",
      body,
      props.course ? undefined : pendingKey,
    );
    emit("saved", course);
  } catch (cause) {
    error.value = message(cause);
    stale.value =
      cause instanceof ApiError && cause.status === 409 && !!props.course;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <dialog
    ref="dialog"
    class="dialog"
    aria-labelledby="course-form-title"
    @cancel.prevent="close"
  >
    <form @submit.prevent="save">
      <div class="dialog-heading">
        <h2 id="course-form-title">
          {{ course ? "编辑课程" : "添加一门课程" }}
        </h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭"
          :disabled="busy"
          @click="close"
        >
          ×
        </button>
      </div>
      <p class="muted">
        {{
          course
            ? "更新课程名称和备注。"
            : "先为这门课取个名字，开始整理你的复习空间。"
        }}
      </p>
      <label for="course-name">课程名称</label
      ><input
        id="course-name"
        ref="nameInput"
        v-model="name"
        required
        maxlength="100"
        placeholder="例如：软件工程"
        :disabled="busy || stale"
      />
      <label for="course-remark"
        >课程备注 <span class="optional">选填</span></label
      ><textarea
        id="course-remark"
        v-model="remark"
        rows="4"
        maxlength="1000"
        placeholder="记录班级、学期，或你对这门课的复习安排"
        :disabled="busy || stale"
      ></textarea>
      <p v-if="error" role="alert" class="error">
        {{ error }}<span v-if="stale"> 请关闭窗口，刷新课程后再编辑。</span>
      </p>
      <div class="dialog-actions">
        <button
          type="button"
          class="button secondary"
          :disabled="busy"
          @click="close"
        >
          取消</button
        ><button class="button primary" :disabled="busy || stale">
          {{ busy ? "正在保存…" : course ? "保存修改" : "创建课程" }}
        </button>
      </div>
    </form>
  </dialog>
</template>
