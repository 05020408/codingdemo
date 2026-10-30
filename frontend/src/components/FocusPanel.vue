<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { message, request, type Material, type Page, type Slide } from "../api";
import AppIcon from "./AppIcon.vue";
interface Focus {
  id: string;
  courseId: string;
  content: string;
  masteryStatus: string;
  matchStatus: string;
  version: number;
  method: string;
  warnings: string[];
  errorMessage: string | null;
}
interface Link {
  linkId: string;
  slideId: string;
  materialId: string;
  materialName: string;
  pageNo: number;
  title: string | null;
  origin: string;
  decision: string;
  needsReview: boolean;
  sourceAvailable: boolean;
  previewAvailable: boolean;
  evidenceText: string;
  reason: string;
  version: number;
}
interface Matches {
  items: Link[];
  focusVersion: number;
}
interface Capabilities {
  aiAvailable: boolean;
  readyMaterials: number;
  totalMaterials: number;
}
const props = defineProps<{ courseId: string }>();
const route = useRoute(),
  router = useRouter();
const items = ref<Focus[]>([]),
  total = ref(0),
  loading = ref(true),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  selected = ref<Focus | null>(null),
  links = ref<Link[]>([]),
  draft = ref(""),
  editing = ref(""),
  bulk = ref(false),
  preview = ref<string[]>([]),
  showRejected = ref(false),
  useAi = ref(false),
  caps = ref<Capabilities>({
    aiAvailable: false,
    readyMaterials: 0,
    totalMaterials: 0,
  }),
  resultLoading = ref(false), resultError = ref("");
const page = computed(() => {
  const value = Number(route.query.focusPage || 1);
  return Number.isInteger(value) && value >= 1 && value <= 1000000 ? value : 1;
});
const mastery = computed(() => Object.hasOwn(masteryLabels, String(route.query.mastery)) ? String(route.query.mastery) : "");
const match = computed(() => Object.hasOwn(matchLabels, String(route.query.match)) ? String(route.query.match) : "");
const manualDialog = ref<HTMLDialogElement>(),
  deleteDialog = ref<HTMLDialogElement>(),
  materials = ref<Material[]>([]),
  manualMaterial = ref(""),
  manualPage = ref("1"),
  manualError = ref("");
const detailPanel = ref<HTMLElement>();
let restoredResultPosition = false;
const masteryLabels: Record<string, string> = {
  NOT_STARTED: "未开始",
  IN_PROGRESS: "复习中",
  UNFAMILIAR: "不熟悉",
  MASTERED: "已掌握",
};
const matchLabels: Record<string, string> = {
  NEVER: "尚未定位",
  RUNNING: "正在定位",
  MATCHED: "有候选位置",
  NO_MATCH: "未找到相关页",
  NEEDS_REVIEW: "需要重新核对",
  FAILED: "定位失败",
};
const warningLabels: Record<string, string> = {
  AI_RESULT_UNKNOWN:
    "AI 请求结果未知，本次已回退关键词定位，不会自动重复调用。",
  AI_HTTP_ERROR: "AI 服务暂时不可用，本次已回退关键词定位。",
  AI_INVALID_RESPONSE: "AI 结果未通过原文校验，本次已回退关键词定位。",
  PARTIAL_INDEX: "部分课件尚未就绪，本次只检索已完成索引的课件。",
  AI_DISABLED: "AI 尚未启用，本次使用关键词定位。",
  AI_QUOTA_EXCEEDED: "今日 AI 次数已用完，本次使用关键词定位。",
  AI_ALREADY_ATTEMPTED: "本次任务已尝试过 AI，使用关键词定位。",
};
const selectedMaterial = computed(() =>
  materials.value.find((m) => m.id === manualMaterial.value),
);
let disposed = false,
  timer: ReturnType<typeof setTimeout> | undefined,
  generation = 0,
  resultGeneration = 0,
  createKey = crypto.randomUUID();
async function results(focus: Focus) {
  const epoch = ++resultGeneration;
  resultLoading.value = true;
  resultError.value = "";
  try {
    const data = await request<Matches>(`/api/focuses/${focus.id}/matches?includeRejected=${showRejected.value}`);
    if (!disposed && epoch === resultGeneration && selected.value?.id === focus.id) links.value = data.items;
  } catch (cause) {
    if (!disposed && epoch === resultGeneration) resultError.value = message(cause);
  } finally {
    if (!disposed && epoch === resultGeneration) resultLoading.value = false;
  }
}
function navigateList(target: number, changes: { mastery?: string; match?: string; focus?: string } = {}) {
  return router.replace({ query: { ...route.query, tab: "focus", ...changes, focusPage: target > 1 ? String(target) : undefined } });
}
function filterBy(kind: "mastery" | "match", event: Event) {
  void navigateList(1, { [kind]: (event.target as HTMLSelectElement).value || undefined, focus: undefined });
}
function readerLocation(link: Link) {
  return { path: `/materials/${link.materialId}/read`, query: { page: link.pageNo, focus: selected.value?.id, focusPage: page.value > 1 ? String(page.value) : undefined, mastery: mastery.value || undefined, match: match.value || undefined } };
}
const confirmedCount = computed(() => links.value.filter(l => l.decision === "CONFIRMED" && l.sourceAvailable && !l.needsReview).length);
const selectedLabel = computed(() => confirmedCount.value && selected.value?.matchStatus === "MATCHED" ? "已有确认位置" : matchLabels[selected.value?.matchStatus || "NEVER"]);
async function select(focus: Focus) {
  generation++;
  clearTimeout(timer);
  selected.value = focus;
  resultGeneration++;
  editing.value = focus.content;
  links.value = [];
  await router.replace({
    query: { ...route.query, tab: "focus", focus: focus.id },
  });
  try {
    await results(focus);
    if (!disposed && selected.value?.id === focus.id && (focus.matchStatus === "RUNNING" || caps.value.readyMaterials < caps.value.totalMaterials))
      timer = setTimeout(() => load(true), 2500);
  } catch (cause) {
    error.value = message(cause);
  }
}
async function load(quiet = false) {
  clearTimeout(timer);
  const current = ++generation;
  if (!quiet) loading.value = true;
  try {
    const [data, capabilities] = await Promise.all([
      request<Page<Focus>>(
        `/api/courses/${props.courseId}/focuses?${new URLSearchParams({ page: String(page.value), size: "20", ...(mastery.value ? { masteryStatus: mastery.value } : {}), ...(match.value ? { matchStatus: match.value } : {}) })}`,
      ),
      request<Capabilities>(
        `/api/courses/${props.courseId}/focus-capabilities`,
      ),
    ]);
    if (disposed || current !== generation) return;
    if (page.value > 1 && data.items.length === 0) {
      await navigateList(Math.max(1, Math.ceil(data.total / 20)));
      return;
    }
    items.value = data.items;
    total.value = data.total;
    caps.value = capabilities;
    if (!capabilities.aiAvailable) useAi.value = false;
    const currentId = typeof route.query.focus === "string" ? route.query.focus : "";
    let found = data.items.find((f) => f.id === currentId);
    if (!found && currentId && route.query.focus === currentId) {
      try {
        const candidate = await request<Focus>(`/api/focuses/${encodeURIComponent(currentId)}`);
        if (candidate.courseId === props.courseId && (!mastery.value || candidate.masteryStatus === mastery.value) && (!match.value || candidate.matchStatus === match.value)) found = candidate;
      } catch {}
    }
    if (disposed || current !== generation) return;
    if (!found) found = data.items[0];
    if (found) {
      const different = selected.value?.id !== found.id;
      selected.value = found;
      if (different) editing.value = found.content;
      await results(found);
    } else {
      selected.value = null;
      links.value = [];
      resultGeneration++; resultLoading.value = false;
    }
    if (!disposed && current === generation) error.value = "";
  } catch (cause) {
    if (!disposed && current === generation) error.value = message(cause);
  } finally {
    if (!disposed && current === generation) {
      loading.value = false;
      await nextTick();
      if (!restoredResultPosition && route.hash === "#focus-results" && detailPanel.value) {
        restoredResultPosition = true;
        detailPanel.value.scrollIntoView({ block: "start" });
      }
      if (
        items.value.some((f) => f.matchStatus === "RUNNING") ||
        selected.value?.matchStatus === "RUNNING" ||
        caps.value.readyMaterials < caps.value.totalMaterials
      )
        timer = setTimeout(() => load(true), 2500);
    }
  }
}
async function perform(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await action();
  } catch (cause) {
    const text = message(cause);
    await load(true);
    error.value = text;
  } finally {
    busy.value = false;
  }
}
async function split() {
  await perform(async () => {
    const data = await request<{ items: string[] }>(
      `/api/courses/${props.courseId}/focus-split-previews`,
      "POST",
      { text: draft.value },
      crypto.randomUUID(),
    );
    preview.value = data.items;
  });
}
async function create() {
  await perform(async () => {
    let createdId = "";
    if (bulk.value) {
      if (!preview.value.length) return;
      const created = await request<Focus[]>(
        `/api/courses/${props.courseId}/focuses/batch`,
        "POST",
        { items: preview.value, originalInput: draft.value },
        createKey,
      );
      createdId = created[0]?.id || "";
    } else {
      const created = await request<Focus>(
        `/api/courses/${props.courseId}/focuses`,
        "POST",
        { content: draft.value },
        createKey,
      );
      createdId = created.id;
    }
    draft.value = "";
    preview.value = [];
    createKey = crypto.randomUUID();
    notice.value = "重点已保存，可以开始定位。";
    await navigateList(1, { focus: createdId, mastery: undefined, match: undefined });
    await load(true);
  });
}
async function save() {
  if (!selected.value) return;
  const focus = selected.value;
  await perform(async () => {
    selected.value = await request<Focus>(`/api/focuses/${focus.id}`, "PATCH", {
      content: editing.value,
      expectedVersion: focus.version,
    });
    notice.value = "重点已更新，原有人工确认的位置已保留，请重新核对。";
    await load(true);
  });
}
async function changeMastery(event: Event) {
  if (!selected.value) return;
  const focus = selected.value,
    value = (event.target as HTMLSelectElement).value;
  await perform(async () => {
    selected.value = await request<Focus>(`/api/focuses/${focus.id}`, "PATCH", {
      masteryStatus: value,
      expectedVersion: focus.version,
    });
    await load(true);
  });
}
async function locate() {
  if (!selected.value) return;
  const focus = selected.value;
  await perform(async () => {
    await request(
      `/api/focuses/${focus.id}/locate`,
      "POST",
      { expectedVersion: focus.version, useAi: useAi.value },
      crypto.randomUUID(),
    );
    notice.value = "定位任务已提交，完成后会自动显示候选页面。";
    await load(true);
  });
}
async function decide(link: Link, decision: string) {
  if (!selected.value) return;
  const focus = selected.value;
  await perform(async () => {
    await request(`/api/focuses/${focus.id}/matches/${link.slideId}`, "PUT", {
      decision,
      expectedFocusVersion: focus.version,
      expectedLinkVersion: link.version,
    });
    await load(true);
    notice.value = decision === "CONFIRMED" ? "已确认相关位置，可以继续对照课件复习。" : "已排除此页；勾选显示已排除的位置可再次查看。";
  });
}
async function showManual() {
  manualError.value = "";
  await perform(async () => {
    let all: Material[] = [];
    for (let n = 1; ; n++) {
      const data = await request<Page<Material>>(
        `/api/courses/${props.courseId}/materials?page=${n}&size=100`,
      );
      all.push(...data.items);
      if (all.length >= data.total) break;
    }
    materials.value = all;
    manualMaterial.value = all[0]?.id ?? "";
    manualPage.value = "1";
    await nextTick();
    manualDialog.value?.showModal();
  });
}
async function addManual() {
  if (!selected.value || !selectedMaterial.value) return;
  const focus = selected.value,
    target = Number(manualPage.value);
  if (
    !Number.isInteger(target) ||
    target < 1 ||
    target > selectedMaterial.value.slideCount
  ) {
    manualError.value = "请输入课件范围内的整数页码。";
    return;
  }
  busy.value = true;
  manualError.value = "";
  try {
    const data = await request<Page<Slide>>(
      `/api/materials/${manualMaterial.value}/slides?page=${target}&size=1`,
    );
    const slide = data.items[0];
    if (!slide) throw new Error("找不到该页面");
    const prior = await request<Matches>(
      `/api/focuses/${focus.id}/matches?includeRejected=true`,
    );
    const existing = prior.items.find((l) => l.slideId === slide.id);
    await request(`/api/focuses/${focus.id}/matches/${slide.id}`, "PUT", {
      decision: "CONFIRMED",
      expectedFocusVersion: focus.version,
      expectedLinkVersion: existing?.version ?? null,
    });
    manualDialog.value?.close();
    notice.value = "已手动关联课件页面。";
    await load(true);
  } catch (cause) {
    manualError.value = message(cause);
  } finally {
    busy.value = false;
  }
}
async function remove() {
  if (!selected.value) return;
  const focus = selected.value;
  await perform(async () => {
    await request(
      `/api/focuses/${focus.id}?expectedVersion=${focus.version}`,
      "DELETE",
      undefined,
      crypto.randomUUID(),
    );
    deleteDialog.value?.close();
    selected.value = null;
    await router.replace({ query: { tab: "focus" } });
    notice.value = "重点已删除。";
    await load(true);
  });
}
watch(draft, () => {
  preview.value = [];
  createKey = crypto.randomUUID();
});
watch(bulk, () => {
  preview.value = [];
  createKey = crypto.randomUUID();
});
watch(showRejected, () => {
  if (selected.value)
    void results(selected.value).catch(
      (cause) => (error.value = message(cause)),
    );
});
watch([page, mastery, match], () => { void load(); });
watch(() => route.query.focus, value => {
  if (!loading.value && value && value !== selected.value?.id) void load(true);
});
onMounted(() => load());
onBeforeUnmount(() => {
  disposed = true;
  generation++;
  resultGeneration++;
  clearTimeout(timer);
});
</script>

<template>
  <section class="focus-section" aria-labelledby="focus-title">
    <div class="section-line">
      <h2 id="focus-title">
        复习重点 <span class="count">{{ total }}</span>
      </h2>
      <span
        >{{ caps.readyMaterials }} /
        {{ caps.totalMaterials }} 份课件可检索</span
      >
    </div>
    <ol class="focus-path" aria-label="重点复习步骤"><li><span>1</span>记下重点</li><li><span>2</span>定位课件原页</li><li><span>3</span>核对并确认</li></ol>
    <form
      class="focus-create"
      @submit.prevent="bulk && !preview.length ? split() : create()"
    >
      <label for="focus-draft">记下老师划的重点，找到课件中的原文</label>
      <textarea
        id="focus-draft"
        v-model="draft"
        :maxlength="bulk ? 10000 : 2000"
        rows="2"
        :placeholder="
          bulk ? '每行输入一个重点，可带列表编号' : '例如：敏捷开发的四个价值观'
        "
        :disabled="busy"
      ></textarea>
      <div class="focus-actions">
        <label class="check-label"
          ><input
            v-model="bulk"
            type="checkbox"
            :disabled="busy"
          />按行批量添加</label
        ><span class="muted"
          >{{ draft.length }} / {{ bulk ? 10000 : 2000 }}</span
        ><button class="button primary" :disabled="busy || !draft.trim()">
          {{
            busy
              ? "处理中…"
              : bulk && !preview.length
                ? "预览拆分结果"
                : bulk
                  ? "确认添加 " + preview.length + " 条"
                  : "添加重点"
          }}
        </button>
      </div>
      <ol v-if="preview.length" class="split-preview">
        <li v-for="(text, i) in preview" :key="i">
          <input
            v-model="preview[i]"
            :aria-label="`第 ${i + 1} 条重点`"
            maxlength="2000"
          />
        </li>
      </ol>
      <p v-if="preview.length" class="muted">
        已按换行和编号拆分，可修改后确认添加。
      </p>
      <p class="focus-create-help">先保存，再定位。关键词定位可独立使用；有候选页后，请对照原文确认。</p>
    </form>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <div v-if="error" role="alert" class="error">
      <p>{{ error }}</p>
      <button class="text-button" @click="load()">刷新状态</button>
    </div>
    <div v-if="loading" class="state-panel" role="status">
      正在加载复习重点…
    </div>
    <div v-else-if="!items.length && !selected && !mastery && !match && !error" class="state-panel">
      <h3>从一个重点开始复习</h3>
      <p>
        保存重点后，知笺会从这门课的课件中寻找相关页面。你可以确认位置、手动补充，并记录掌握情况。
      </p>
    </div>
    <div v-else class="focus-layout">
      <aside class="focus-list" aria-label="重点清单">
        <div class="focus-list-heading"><h3>重点清单</h3><span>{{ total }} 条</span></div>
        <div class="focus-filters"><label for="focus-filter">掌握情况</label
        ><select id="focus-filter" :value="mastery" :disabled="busy" @change="filterBy('mastery', $event)">
          <option value="">全部重点</option>
          <option
            v-for="(label, value) in masteryLabels"
            :key="value"
            :value="value"
          >
            {{ label }}
          </option>
        </select>
        <label for="focus-match-filter">定位状态</label><select id="focus-match-filter" :value="match" :disabled="busy" @change="filterBy('match', $event)"><option value="">全部定位状态</option><option v-for="(label, value) in matchLabels" :key="value" :value="value">{{ label }}</option></select></div>
        <p v-if="!items.length" class="focus-filter-empty">没有符合筛选条件的重点。<button class="text-button" @click="navigateList(1, { mastery: undefined, match: undefined, focus: undefined })">清除筛选</button></p>
        <button
          v-for="focus in items"
          :key="focus.id"
          class="focus-item"
          :class="{ selected: selected?.id === focus.id }"
          :aria-pressed="selected?.id === focus.id"
          :disabled="busy"
          @click="select(focus)"
        >
          <strong>{{ focus.content }}</strong
          ><span class="focus-item-meta"><span>{{ masteryLabels[focus.masteryStatus] }}</span><span :class="['focus-match-label', focus.matchStatus.toLowerCase()]">{{ matchLabels[focus.matchStatus] }}</span></span>
        </button>
        <div v-if="total > 20" class="focus-pagination">
          <button
            class="text-button"
            :disabled="page === 1 || busy"
            @click="
              navigateList(page - 1);
            "
          >
            上一页</button
          ><span>{{ page }} / {{ Math.ceil(total / 20) }}</span
          ><button
            class="text-button"
            :disabled="page * 20 >= total || busy"
            @click="
              navigateList(page + 1);
            "
          >
            下一页
          </button>
        </div>
      </aside>
      <div v-if="selected" id="focus-results" ref="detailPanel" class="focus-detail" :aria-busy="resultLoading">
        <div class="section-line">
          <span class="eyebrow">当前复习重点</span>
          <button
            class="text-button danger-text"
            :disabled="busy"
            @click="deleteDialog?.showModal()"
          >
            删除重点
          </button>
        </div>
        <h3 class="focus-selected-content">{{ selected.content }}</h3>
        <div class="focus-mastery-row"><label for="focus-mastery">掌握情况</label><select id="focus-mastery" :value="selected.masteryStatus" :disabled="busy" @change="changeMastery"><option v-for="(label, value) in masteryLabels" :key="value" :value="value">{{ label }}</option></select></div>
        <details class="focus-editor"><summary><AppIcon name="edit" :size="15" />编辑重点内容<span v-if="editing !== selected.content">有未保存的修改</span></summary>
        <label for="focus-edit">重点内容</label
        ><textarea
          id="focus-edit"
          v-model="editing"
          rows="3"
          maxlength="2000"
          :disabled="busy"
        ></textarea>
        <div class="focus-actions">
          <button
            class="button secondary"
            :disabled="busy || editing === selected.content || !editing.trim()"
            @click="save"
          >
            保存修改</button
          ><button class="text-button" :disabled="busy || editing === selected.content" @click="editing = selected.content">取消修改</button>
        </div>
        </details>
        <div class="locate-panel">
          <div>
            <strong>在课件中定位</strong>
            <p class="muted">先查看原文依据，再确认是否与重点相关。</p>
          </div>
          <button
            class="button primary"
            :disabled="
              busy ||
              selected.matchStatus === 'RUNNING' ||
              !caps.readyMaterials ||
              editing !== selected.content
            "
            @click="locate"
          >
            {{
              selected.matchStatus === "RUNNING" ? "正在定位…" : selected.matchStatus === "NEVER" ? "定位相关页" : "重新定位"
            }}
          </button>
          <label v-if="caps.aiAvailable" class="check-label"
            ><input
              v-model="useAi"
              type="checkbox"
              :disabled="busy || selected.matchStatus === 'RUNNING'"
            />使用 DeepSeek 辅助判断</label
          ><small v-if="useAi" class="muted"
            >将发送当前重点与最多 20 个候选页的文字片段，会使用 AI
            调用额度。</small
          ><small v-if="!caps.readyMaterials" class="muted"
            >请先上传课件，并等待文字提取和搜索索引完成。</small
          >
          <small v-if="editing !== selected.content" class="muted">请先保存或取消重点修改，再进行定位。</small>
          <small v-if="!useAi && caps.readyMaterials" class="muted">使用关键词检索课件文字，不调用 AI。</small>
        </div>
        <p class="focus-result-status" role="status">
          {{ selectedLabel
          }}<span v-if="selected.matchStatus === 'MATCHED'">
            ·
            {{
              selected.method === "AI_RERANK"
                ? "DeepSeek 辅助判断"
                : "关键词检索"
            }}</span
          >
          <span v-if="confirmedCount"> · {{ confirmedCount }} 个位置已确认</span>
        </p>
        <p v-if="selected.errorMessage" class="error" role="alert">
          {{ selected.errorMessage }}
        </p>
        <p v-for="warning in selected.warnings" :key="warning" class="notice">
          {{
            warningLabels[warning] ||
            "AI 暂时无法返回有效结果，本次使用关键词定位。"
          }}
        </p>
        <div class="section-line">
          <h3>
            课件中的相关页面 <span class="count">{{ links.length }}</span>
          </h3>
          <button class="text-button" :disabled="busy" @click="showManual">
            <AppIcon name="add" :size="15" />手动关联
          </button>
        </div>
        <label class="check-label"
          ><input
            v-model="showRejected"
            type="checkbox"
          />显示已排除的位置</label
        >
        <p v-if="resultLoading && !links.length" class="state-panel" role="status">正在读取定位结果…</p>
        <p v-else-if="resultError" class="error" role="alert">{{ resultError }}<button class="text-button" @click="results(selected)">重新加载结果</button></p>
        <p v-else-if="!links.length" class="focus-no-results">
          {{
            selected.matchStatus === "NO_MATCH"
              ? "没有找到相关页。可以换一种重点表述，或手动关联课件页。"
              : selected.matchStatus === "RUNNING" ? "正在检索已就绪的课件，完成后自动显示结果。" : "定位后，候选页面会显示在这里。也可以手动关联已知页码。"
          }}
        </p>
        <article
          v-for="link in links"
          :key="link.linkId"
          class="match-card"
          :class="{ rejected: link.decision === 'REJECTED', confirmed: link.decision === 'CONFIRMED' && !link.needsReview }"
        >
          <div class="section-line">
            <div class="match-source"><span class="match-page">原页 {{ String(link.pageNo).padStart(3, '0') }}</span><strong>{{ link.materialName }}</strong></div
            ><span class="status-chip" :class="link.decision.toLowerCase()">{{
              link.decision === "CONFIRMED"
                ? "已确认"
                : link.decision === "REJECTED"
                  ? "已排除"
                  : "待确认"
            }}</span>
          </div>
          <p v-if="link.title" class="match-title">{{ link.title }}</p>
          <span class="match-evidence-label">{{ link.origin === 'MANUAL' ? '手动关联的页面原文' : '匹配到的课件原文' }}</span>
          <blockquote>
            {{ link.evidenceText || "此页尚无可提取文字，已保留手动关联。" }}
          </blockquote>
          <p class="match-method">{{ link.origin === 'MANUAL' ? '手动关联' : '自动定位' }}<span v-if="link.reason && link.reason !== '手动关联'"> · {{ link.reason }}</span></p>
          <p v-if="!link.sourceAvailable" class="error">
            来源课件已删除，此处仅保留历史位置记录。
          </p>
          <p v-else-if="link.needsReview" class="notice">
            重点或课件内容已更新，请重新核对这个位置。
          </p>
          <div class="focus-actions">
            <RouterLink
              v-if="link.previewAvailable"
              class="button secondary"
              :to="readerLocation(link)"
              >查看课件原页 →</RouterLink
            ><span v-else-if="link.sourceAvailable" class="muted"
              >课件预览尚未就绪</span
            ><button
              v-if="
                link.sourceAvailable &&
                (link.decision !== 'CONFIRMED' || link.needsReview)
              "
              class="text-button"
              :disabled="busy"
              @click="decide(link, 'CONFIRMED')"
            >
              确认相关</button
            ><button
              v-if="link.decision !== 'REJECTED'"
              class="text-button"
              :disabled="busy"
              @click="decide(link, 'REJECTED')"
            >
              排除此页
            </button>
          </div>
        </article>
      </div>
    </div>
    <dialog
      ref="manualDialog"
      class="dialog"
      aria-labelledby="manual-title"
      @cancel="busy && $event.preventDefault()"
    >
      <form @submit.prevent="addManual">
        <h2 id="manual-title">手动关联课件页</h2>
        <label for="manual-material">课件</label
        ><select
          id="manual-material"
          v-model="manualMaterial"
          :disabled="busy"
          @change="manualPage = '1'"
        >
          <option
            v-for="material in materials"
            :key="material.id"
            :value="material.id"
          >
            {{ material.displayName }}（{{ material.slideCount }} 页）
          </option></select
        ><label for="manual-page">原始页码</label
        ><input
          id="manual-page"
          v-model="manualPage"
          type="number"
          min="1"
          :max="selectedMaterial?.slideCount"
          :disabled="busy"
        />
        <p v-if="!materials.length" class="muted">请先上传课件。</p>
        <p v-if="manualError" class="error" role="alert">{{ manualError }}</p>
        <div class="dialog-actions">
          <button
            class="button secondary"
            type="button"
            :disabled="busy"
            @click="manualDialog?.close()"
          >
            取消</button
          ><button class="button primary" :disabled="busy || !manualMaterial">
            确认关联
          </button>
        </div>
      </form>
    </dialog>
    <dialog
      ref="deleteDialog"
      class="dialog"
      aria-labelledby="focus-delete-title"
      @cancel="busy && $event.preventDefault()"
    >
      <form @submit.prevent="remove">
        <h2 id="focus-delete-title">删除这条重点？</h2>
        <p>重点及其页面关联将被删除。</p>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <div class="dialog-actions">
          <button
            type="button"
            class="button secondary"
            :disabled="busy"
            @click="deleteDialog?.close()"
          >
            取消</button
          ><button class="button danger" :disabled="busy">确认删除</button>
        </div>
      </form>
    </dialog>
  </section>
</template>
