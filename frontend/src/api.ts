export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public code: string,
    public requestId?: string,
  ) {
    super(message);
  }
}
let csrf: { headerName: string; token: string } | null = null;
export async function refreshCsrf() {
  const response = await fetch("/api/auth/csrf", {
    credentials: "same-origin",
  });
  if (!response.ok) throw new Error("无法连接服务，请确认后端已启动");
  csrf = (await response.json()).data;
}
export async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
  key?: string,
): Promise<T> {
  const headers: Record<string, string> = {};
  if (method !== "GET") {
    if (!csrf) await refreshCsrf();
    headers[csrf!.headerName] = csrf!.token;
    if (body !== undefined) headers["Content-Type"] = "application/json";
  }
  if (key) headers["Idempotency-Key"] = key;
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      credentials: "same-origin",
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new Error("网络连接失败，请检查服务后重试");
  }
  if (response.status === 204) return undefined as T;
  const envelope = await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith("/api/auth/"))
      window.dispatchEvent(new Event("review-session-expired"));
    if (response.status === 403) {
      csrf = null;
      await refreshCsrf().catch(() => {});
    }
    throw new ApiError(
      envelope?.message ?? "请求失败，请稍后重试",
      response.status,
      envelope?.code ?? "REQUEST_FAILED",
      envelope?.requestId,
    );
  }
  return envelope.data as T;
}
export interface User {
  id: string;
  username: string;
  displayName: string;
}
export interface Course {
  id: string;
  name: string;
  remark: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}
export interface Job {
  id: string;
  status: string;
}
export function message(error: unknown): string {
  return error instanceof Error ? error.message : "操作失败，请稍后重试";
}
