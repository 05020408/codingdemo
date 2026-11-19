export interface AnswerNoteClip {
  id: string;
  markdown: string;
}

interface Source {
  slideId: string;
  materialId: string;
  materialName: string;
  pageNo: number;
  available: boolean;
  changed: boolean;
}

interface AnswerTurn {
  id: string;
  question: string;
  sources: readonly Source[];
  answer: {
    explanation: string;
    supplement: string;
    limitations: string;
    citations: readonly { slideId: string }[];
  };
}

function plainMarkdown(value: string) {
  return value.replace(/\r\n?/g, "\n")
    .replace(/[\\`*_<>\[\]{}()#!|~$+-]/g, "\\$&")
    .replace(/^(\s*\d+)\./gm, "$1\\.");
}

function sourceLabel(source: Source, materialId: string) {
  const page = "第 " + source.pageNo + " 页";
  if (source.materialId !== materialId || !source.available) {
    const citation = source.materialId === materialId ? page : plainMarkdown(source.materialName) + " · " + page;
    return citation + (source.available ? "" : "（来源不可用）");
  }
  return "[" + page + "](#page-" + source.pageNo + ")" + (source.changed ? "（课件已更新）" : "");
}

export function formatAnswerNote(turn: AnswerTurn, materialId: string): AnswerNoteClip {
  const question = plainMarkdown(turn.question.replace(/\s+/g, " ").trim());
  const parts = ["## " + (question || "AI 答疑")];
  const firstSource = turn.sources[0];
  if (firstSource) parts.push("提问页：" + sourceLabel(firstSource, materialId));
  const cited = new Set(turn.answer.citations.map(citation => citation.slideId));
  const pages = turn.sources.filter(source => cited.has(source.slideId));
  if (pages.length) {
    parts.push("回答引用：" + [...new Set(pages.map(source => sourceLabel(source, materialId)))].join("、"));
  }
  for (const [label, text] of [
    ["AI 解释", turn.answer.explanation],
    ["补充解释与例子", turn.answer.supplement],
    ["回答限制", turn.answer.limitations],
  ]) {
    if (text.trim()) parts.push("### " + label + "\n\n" + plainMarkdown(text.trim()));
  }
  return { id: turn.id, markdown: parts.join("\n\n") };
}

function marker(clip: AnswerNoteClip) {
  // A Markdown reference definition is hidden in the preview and survives saving and export.
  return "[zhijian-note-answer-" + clip.id + "]: #ai-answer";
}

function openFence(markdown: string) {
  let opened = "";
  for (const line of markdown.split("\n")) {
    const matched = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
    if (!matched) continue;
    const [, fence = "", rest = ""] = matched;
    if (opened) {
      if (fence[0] === opened[0] && fence.length >= opened.length && !rest.trim()) opened = "";
    } else if (fence[0] === "~" || !rest.includes("`")) opened = fence;
  }
  return opened;
}

export function appendAnswerNote(markdown: string, clip: AnswerNoteClip) {
  const reference = marker(clip);
  const existing = ("\n" + markdown + "\n").indexOf("\n" + reference + "\n");
  const blockOffset = reference.length + 2;
  if (existing >= 0) {
    return { markdown, position: Math.min(markdown.length, existing + blockOffset), added: false };
  }
  const fence = openFence(markdown);
  const separator = fence ? "\n" + fence + "\n\n" : markdown ? "\n\n" : "";
  const prefix = markdown + separator + reference + "\n\n";
  return { markdown: prefix + clip.markdown + "\n", position: prefix.length, added: true };
}
