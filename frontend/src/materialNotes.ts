export interface NoteEntry {
  materialId: string;
  materialName: string;
  sourceStatus: "AVAILABLE" | "DELETED" | "UNAVAILABLE";
  updatedAt: string;
}

export interface NoteDocument extends Omit<NoteEntry, "updatedAt"> {
  markdown: string;
  version: number;
  pageCount: number;
  updatedAt: string | null;
}

export function noteEndpoint(courseId: string, materialId?: string) {
  const list = "/api/courses/" + encodeURIComponent(courseId) + "/notes";
  return materialId ? list + "/" + encodeURIComponent(materialId) : list;
}

export function noteFilename(materialName: string) {
  const name = materialName.replace(/\.pptx$/i, "").replace(/[\\/:*?"<>|\x00-\x1f\x7f]/g, "_").trim();
  return (name || "课件笔记") + ".md";
}
