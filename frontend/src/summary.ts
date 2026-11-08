export interface SummaryVersion {
  id: string;
  materialId: string;
  number: number;
  origin: string;
  status: string;
  markdown: string | null;
  stale: boolean;
  totalPages: number;
  emptyPages: number;
  completedSteps: number;
  totalSteps: number;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  workflow: string;
  phaseLabel: string;
  remainingRequests: number;
  planningPending: boolean;
  resumeWillPlan: boolean;
}
export interface SummaryState {
  materialId: string;
  courseId: string;
  materialName: string;
  version: number;
  current: SummaryVersion | null;
  versions: SummaryVersion[];
  estimatedRequests: number;
  remainingToday: number;
  aiAvailable: boolean;
  blockedReason: string;
  estimatedRequestsLowerBound: boolean;
}
export const summaryStatus: Record<string, string> = {
  QUEUED: "等待生成",
  RUNNING: "正在整理",
  SUCCEEDED: "已完成",
  FAILED: "未完成",
  CANCELLED: "已停止",
};
