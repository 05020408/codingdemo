<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from "vue-router";
import { ApiError, message, request } from "../api";
import type { SummaryState, SummaryVersion } from "../summary";
import { summaryStatus } from "../summary";
import MarkdownDocument from "../components/MarkdownDocument";
import "../summary.css";
const route = useRoute(),
  id = computed(() => String(route.params.id));
const state = ref<SummaryState | null>(null),
  chosen = ref(""),
  draft = ref(""),
  editing = ref(false),
  savedDraft = ref("");
const busy = ref(false),
  error = ref(""),
  notice = ref(""),
  loading = ref(true),
  confirmGeneration = ref(false);
const version = computed(
  () =>
    state.value?.versions.find((v) => v.id === chosen.value) ??
    (state.value?.current?.id === chosen.value ? state.value.current : null),
);
const active = computed(() =>
  state.value?.versions.find((v) => ["QUEUED", "RUNNING"].includes(v.status)),
);
const dirty = computed(() => editing.value && draft.value !== savedDraft.value);
const uncertain = ref(false),
  leaveDialog = ref<HTMLDialogElement>();
let leaveResolve: ((value: boolean) => void) | null = null;
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false,
  epoch = 0;
let pending: {
  path: string;
  method: string;
  body?: unknown;
  key: string;
  edit: boolean;
} | null = null;
function decideLeave(value: boolean) {
  leaveDialog.value?.close();
  leaveResolve?.(value);
  leaveResolve = null;
}
async function mayLeave() {
  if (!dirty.value && !uncertain.value) return true;
  return new Promise<boolean>((resolve) => {
    leaveResolve = resolve;
    leaveDialog.value?.showModal();
  });
}
onBeforeRouteLeave(mayLeave);
onBeforeRouteUpdate(mayLeave);
function beforeUnload(e: BeforeUnloadEvent) {
  if (dirty.value || uncertain.value) {
    e.preventDefault();
    e.returnValue = "";
  }
}
window.addEventListener("beforeunload", beforeUnload);
async function load() {
  const currentId = id.value,
    token = epoch;
  try {
    const data = await request<SummaryState>(
      `/api/materials/${currentId}/summary`,
    );
    if (disposed || token !== epoch) return;
    const oldCurrent = state.value?.current?.id;
    state.value = data;
    if (
      !chosen.value ||
      (!editing.value &&
        chosen.value === oldCurrent &&
        data.current?.id !== oldCurrent)
    )
      chosen.value = data.current?.id ?? data.versions[0]?.id ?? "";
  } catch (cause) {
    if (!disposed && token === epoch) error.value = message(cause);
  } finally {
    if (!disposed && token === epoch) {
      loading.value = false;
      clearTimeout(timer);
      if (active.value) timer = setTimeout(load, 1800);
    }
  }
}
async function mutate(
  path: string,
  method = "POST",
  body?: unknown,
  edit = false,
) {
  if (busy.value) return;
  if (!pending)
    pending = { path, method, body, key: crypto.randomUUID(), edit };
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const operation = pending;
    const data = await request<SummaryVersion | SummaryState>(
      operation.path,
      operation.method,
      operation.body,
      operation.key,
    );
    if (operation.edit) {
      editing.value = false;
      savedDraft.value = draft.value;
    }
    if ("number" in data) chosen.value = data.id;
    pending = null;
    uncertain.value = false;
    confirmGeneration.value = false;
    await load();
    notice.value = "操作已保存。";
  } catch (cause) {
    error.value = message(cause);
    uncertain.value = !(cause instanceof ApiError);
    if (!uncertain.value) pending = null;
  } finally {
    busy.value = false;
  }
}
async function select(value: string) {
  if (dirty.value && !(await mayLeave())) return;
  editing.value = false;
  chosen.value = value;
}
function edit() {
  draft.value = version.value?.markdown ?? "";
  savedDraft.value = draft.value;
  editing.value = true;
}
async function cancelEdit() {
  if (dirty.value && !(await mayLeave())) return;
  editing.value = false;
}
watch(
  id,
  () => {
    epoch++;
    clearTimeout(timer);
    state.value = null;
    chosen.value = "";
    editing.value = false;
    pending = null;
    uncertain.value = false;
    error.value = "";
    notice.value = "";
    loading.value = true;
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  disposed = true;
  epoch++;
  clearTimeout(timer);
  window.removeEventListener("beforeunload", beforeUnload);
  leaveResolve?.(false);
});
</script>

<template>
  <section class="summary-page">
    <div v-if="loading" class="state-panel" role="status">
      正在读取课件摘要…
    </div>
    <template v-if="state">
      <header class="summary-heading">
        <div>
          <RouterLink class="back-link" :to="`/courses/${state.courseId}`"
            >← 返回课程</RouterLink
          >
          <p class="summary-eyebrow">课件核心摘要</p>
          <h1>{{ state.materialName }}</h1>
          <p class="muted">核心内容与复习清单</p>
        </div>
        <RouterLink class="button secondary" :to="`/materials/${id}/read`"
          >阅读课件 →</RouterLink
        >
      </header>
      <p v-if="error" class="error" role="alert">
        {{ error }}
        <button v-if="!uncertain" class="text-button" @click="load">
          刷新状态
        </button>
      </p>
      <p v-if="notice" class="notice" role="status">{{ notice }}</p>
      <p v-if="uncertain" class="notice">
        上次请求结果尚未确认。请用同一请求确认结果，避免重复生成或保存。<button
          class="text-button"
          :disabled="busy"
          @click="mutate('')"
        >
          确认上次请求
        </button>
      </p>
      <div v-if="active" class="summary-progress" role="status">
        <strong
          >{{ summaryStatus[active.status] }} · {{ active.completedSteps }}/{{
            active.totalSteps
          }}
          步</strong
        >
        <p>正在整理，已有摘要仍可查看。</p>
        <progress
          :max="active.totalSteps || 1"
          :value="active.completedSteps"
        /><button
          class="text-button"
          :disabled="busy || uncertain"
          @click="mutate(`/api/summary-generations/${active.id}/cancel`)"
        >
          停止生成
        </button>
      </div>
      <div class="summary-layout">
        <aside class="summary-sidebar">
          <h2>摘要版本</h2>
          <p v-if="!state.versions.length" class="muted">尚未生成摘要</p>
          <button
            v-for="v in state.versions"
            :key="v.id"
            class="summary-version"
            :class="{ selected: chosen === v.id }"
            :disabled="busy || uncertain"
            @click="select(v.id)"
          >
            <strong
              >v{{ v.number }} ·
              {{ v.origin === "USER" ? "人工编辑" : "AI 整理" }}</strong
            ><span
              >{{ summaryStatus[v.status]
              }}{{ state.current?.id === v.id ? " · 当前采用" : ""
              }}{{ v.stale ? " · 来源已变化" : "" }}</span
            >
          </button>
          <div class="summary-generate">
            <p>
              预计 {{ state.estimatedRequests }} 次请求 · 今日剩余
              {{ state.remainingToday }} 次
            </p>
            <details class="compact-help"><summary>生成说明</summary><p>将课件提取文字交由 DeepSeek 整理，与答疑、定位共用每日额度。图片、图表和公式可能未覆盖，请核对原课件。</p></details>
            <button
              class="button primary"
              :disabled="
                busy ||
                uncertain ||
                !!active ||
                editing ||
                !state.aiAvailable ||
                !!state.blockedReason ||
                state.estimatedRequests > state.remainingToday
              "
              @click="confirmGeneration = !confirmGeneration"
            >
              {{ state.current ? "重新生成摘要" : "生成核心摘要" }}
            </button>
            <p v-if="state.blockedReason" class="muted">
              {{ state.blockedReason }}
            </p>
            <p v-else-if="!state.aiAvailable" class="muted">
              AI 尚未启用，已有摘要仍可阅读和编辑。
            </p>
            <p
              v-else-if="state.estimatedRequests > state.remainingToday"
              class="muted"
            >
              当前额度不足，已保存的摘要不会受影响。
            </p>
            <div v-if="confirmGeneration" class="summary-confirm">
              <p>
                将把本课件提取的文字发送给
                AI。图片内容可能未覆盖，最终摘要请对照原课件核验。
              </p>
              <button
                class="button primary"
                :disabled="busy || uncertain"
                @click="mutate(`/api/materials/${id}/summary-generations`)"
              >
                确认生成</button
              ><button class="text-button" @click="confirmGeneration = false">
                取消
              </button>
            </div>
          </div>
        </aside>
        <main class="summary-paper">
          <template v-if="version">
            <div class="summary-paper-meta">
              <span
                >v{{ version.number }} ·
                {{ version.origin === "USER" ? "人工修订" : "AI 生成" }} · 覆盖
                {{ version.totalPages }} 页，其中
                {{ version.emptyPages }} 页无文字</span
              ><span v-if="state.current?.id === version.id">当前采用</span>
            </div>
            <p v-if="version.stale" class="notice">
              课件文字已变化，此摘要可能过期。可查看旧版，建议重新生成；附带答疑时会标明来源状态。
            </p>
            <template v-if="version.status === 'SUCCEEDED'">
              <div class="summary-actions">
                <button
                  v-if="!editing"
                  class="button secondary"
                  :disabled="busy || uncertain"
                  @click="edit"
                >
                  编辑摘要</button
                ><a
                  v-if="!editing"
                  class="button secondary"
                  :href="`/api/summary-versions/${version.id}/download`"
                  >下载 Markdown</a
                ><button
                  v-if="state.current?.id !== version.id && !editing"
                  class="text-button"
                  :disabled="busy || uncertain"
                  @click="
                    mutate(`/api/materials/${id}/summary/current`, 'PUT', {
                      versionId: version.id,
                      expectedVersion: state.version,
                    })
                  "
                >
                  采用此版本
                </button>
              </div>
              <template v-if="editing"
                ><label for="summary-editor"
                  >编辑 Markdown · 保存后创建新版并采用</label
                ><textarea
                  id="summary-editor"
                  v-model="draft"
                  class="summary-editor"
                  maxlength="12000"
                  :disabled="busy || uncertain"
                />
                <p class="muted">
                  {{ draft.length }}/12000 字符 · 内容用于 AI
                  背景时会标为人工编辑。
                </p>
                <div class="summary-actions">
                  <button
                    class="button primary"
                    :disabled="busy || uncertain || !draft.trim()"
                    @click="
                      mutate(
                        `/api/materials/${id}/summary-versions`,
                        'POST',
                        {
                          baseVersionId: version.id,
                          expectedVersion: state.version,
                          markdown: draft,
                        },
                        true,
                      )
                    "
                  >
                    保存为新版本</button
                  ><button
                    class="button secondary"
                    :disabled="busy || uncertain"
                    @click="cancelEdit"
                  >
                    取消编辑
                  </button>
                </div>
                <p class="summary-preview-label">排版预览</p></template
              >
              <MarkdownDocument
                :content="editing ? draft : version.markdown || ''"
              />
            </template>
            <div v-else class="summary-empty">
              <h2>{{ summaryStatus[version.status] }}</h2>
              <p>
                {{
                  version.errorMessage ||
                  "正在整理课件内容，完成后将显示连贯的核心摘要。"
                }}
              </p>
              <button
                v-if="['FAILED', 'CANCELLED'].includes(version.status)"
                class="button secondary"
                :disabled="
                  busy ||
                  uncertain ||
                  !!active ||
                  version.stale ||
                  !state.aiAvailable ||
                  state.remainingToday < 1
                "
                @click="mutate(`/api/summary-generations/${version.id}/retry`)"
              >
                继续生成（消耗额度）
              </button>
            </div>
          </template>
          <div v-else class="summary-empty">
            <h2>还没有摘要</h2>
            <p>
              生成核心内容与复习清单，阅读时可附带提问。
            </p>
          </div>
        </main>
      </div>
    </template>
    <p v-else-if="!loading" class="error">
      {{ error }} <button class="text-button" @click="load">重新加载</button>
    </p>
    <dialog
      ref="leaveDialog"
      class="dialog"
      @cancel.prevent="decideLeave(false)"
    >
      <h2>离开摘要编辑？</h2>
      <p>未保存的修改会丢失；结果未确认的请求可能已经保存。</p>
      <div class="summary-actions">
        <button class="button secondary" @click="decideLeave(false)">
          继续编辑</button
        ><button class="button primary" @click="decideLeave(true)">
          离开页面
        </button>
      </div>
    </dialog>
  </section>
</template>
