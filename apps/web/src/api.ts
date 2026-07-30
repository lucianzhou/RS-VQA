import type {
  ApiError,
  AuditEvent,
  AgentRun,
  AgentSession,
  AgentSessionSummary,
  AgentActionName,
  AgentActionProposal,
  BatchJob,
  ConversationDetail,
  CurrentUser,
  ImageAsset,
  PredictionResponse,
  Project,
  QuestionApiResponse,
  KnowledgeDocument,
  KnowledgeSearchResult,
  SystemStatus,
  ProviderDescriptor,
  ArchiveIndex,
  AnalysisStatistics,
  ReportDetail,
  ReportSummary,
  UserSetting,
} from "./types";

type CsrfTokenResponse = {
  token: string;
  headerName: string;
  parameterName: string;
};

let csrfTokenRequest: Promise<CsrfTokenResponse> | undefined;

function isUnsafeMethod(method?: string) {
  return !["GET", "HEAD", "OPTIONS", "TRACE"].includes((method ?? "GET").toUpperCase());
}

function csrfCookieValue() {
  const prefix = "XSRF-TOKEN=";
  const cookie = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix));
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : undefined;
}

async function csrfToken(signal?: AbortSignal): Promise<CsrfTokenResponse> {
  if (!csrfTokenRequest) {
    csrfTokenRequest = fetch("/api/v1/auth/csrf", {
      method: "GET",
      credentials: "same-origin",
      headers: { Accept: "application/json" },
      signal,
    }).then(async (response) => {
      if (!response.ok) throw new Error(`无法建立安全请求上下文（${response.status}）`);
      const contract = await response.json() as CsrfTokenResponse;
      const cookieToken = csrfCookieValue();
      if (!cookieToken) throw new Error("无法读取安全请求 Cookie。");
      return { ...contract, token: cookieToken };
    }).catch((error) => {
      csrfTokenRequest = undefined;
      throw error;
    });
  }
  return csrfTokenRequest;
}

async function apiFetch<T>(path: string, init?: RequestInit, timeoutMs = 30_000): Promise<T> {
  const requestController = new AbortController();
  let timedOut = false;
  const forwardAbort = () => requestController.abort();
  init?.signal?.addEventListener("abort", forwardAbort, { once: true });
  const timeout = window.setTimeout(() => {
    timedOut = true;
    requestController.abort();
  }, timeoutMs);
  try {
    const csrf = isUnsafeMethod(init?.method)
      ? await csrfToken(requestController.signal)
      : undefined;
    const response = await fetch(path, {
      credentials: "same-origin",
      ...init,
      signal: requestController.signal,
      headers: {
        ...(init?.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
        ...(csrf ? { [csrf.headerName]: csrf.token } : {}),
        ...init?.headers,
      },
    });
    if (response.status === 204) return undefined as T;
    let body: T | ApiError;
    try {
      body = await response.json() as T | ApiError;
    } catch {
      if (!response.ok) {
        if (response.status === 403) csrfTokenRequest = undefined;
        throw new Error(`请求失败（${response.status}）`);
      }
      throw new Error("服务返回了无法解析的响应。");
    }
    if (!response.ok) {
      if (response.status === 403) csrfTokenRequest = undefined;
      const error = body as ApiError;
      throw new Error(error.message || `请求失败（${response.status}）`);
    }
    return body as T;
  } catch (error) {
    if (typeof error === "object" && error !== null && "name" in error && error.name === "AbortError") {
      throw new Error(timedOut ? "请求超时，请检查服务状态后重试。" : "请求已取消。");
    }
    throw error;
  } finally {
    window.clearTimeout(timeout);
    init?.signal?.removeEventListener("abort", forwardAbort);
  }
}

export function demoLogin() {
  return apiFetch<CurrentUser>("/api/v1/auth/demo", { method: "POST" });
}

export function listProjects() {
  return apiFetch<Project[]>("/api/v1/projects");
}

export function createProject(name: string) {
  return apiFetch<Project>("/api/v1/projects", {
    method: "POST",
    body: JSON.stringify({ name }),
  });
}

export function renameProject(projectId: string, name: string) {
  return apiFetch<Project>(`/api/v1/projects/${projectId}`, {
    method: "PATCH",
    body: JSON.stringify({ name }),
  });
}

export function archiveProject(projectId: string) {
  return apiFetch<void>(`/api/v1/projects/${projectId}/archive`, { method: "POST" });
}

export function restoreProject(projectId: string) {
  return apiFetch<void>(`/api/v1/projects/${projectId}/restore`, { method: "POST" });
}

export function createConversation(projectId: string, title = "新分析") {
  return apiFetch<ConversationDetail>(`/api/v1/projects/${projectId}/conversations`, {
    method: "POST",
    body: JSON.stringify({ title }),
  });
}

export function getConversation(conversationId: string) {
  return apiFetch<ConversationDetail>(`/api/v1/conversations/${conversationId}`);
}

export function updateConversation(conversationId: string, update: { title?: string; projectId?: string }) {
  return apiFetch<ConversationDetail>(`/api/v1/conversations/${conversationId}`, {
    method: "PATCH",
    body: JSON.stringify(update),
  });
}

export function archiveConversation(conversationId: string) {
  return apiFetch<void>(`/api/v1/conversations/${conversationId}/archive`, { method: "POST" });
}

export function restoreConversation(conversationId: string) {
  return apiFetch<void>(`/api/v1/conversations/${conversationId}/restore`, { method: "POST" });
}

export function getArchive() {
  return apiFetch<ArchiveIndex>("/api/v1/archive");
}

export function uploadConversationImage(conversationId: string, image: File) {
  const form = new FormData();
  form.append("image", image);
  return apiFetch<ImageAsset>(`/api/v1/conversations/${conversationId}/image`, {
    method: "POST",
    body: form,
  });
}

export function deleteConversationImage(conversationId: string) {
  return apiFetch<void>(`/api/v1/conversations/${conversationId}/image`, { method: "DELETE" });
}

export function askConversation(
  conversationId: string,
  question: string,
  providerId: string,
  modelReleaseId?: string,
  signal?: AbortSignal,
) {
  return apiFetch<QuestionApiResponse>(`/api/v1/conversations/${conversationId}/questions`, {
    method: "POST",
    body: JSON.stringify({ question, providerId, modelReleaseId }),
    signal,
  });
}

export async function askLegacyVqa(
  image: File,
  question: string,
  signal?: AbortSignal,
): Promise<PredictionResponse> {
  const form = new FormData();
  form.append("image", image);
  form.append("question", question);
  return apiFetch<PredictionResponse>("/api/v1/vqa/answers", { method: "POST", body: form, signal });
}

export function listBatchJobs() {
  return apiFetch<BatchJob[]>("/api/v1/batch-jobs");
}

export function getBatchJob(jobId: string) {
  return apiFetch<BatchJob>(`/api/v1/batch-jobs/${jobId}`);
}

export function cancelBatchJob(jobId: string) {
  return apiFetch<BatchJob>(`/api/v1/batch-jobs/${jobId}/cancel`, { method: "POST" });
}

export function retryBatchFailures(jobId: string) {
  return apiFetch<BatchJob>(`/api/v1/batch-jobs/${jobId}/retry-failed`, { method: "POST" });
}

export function listArchivedBatchJobs() {
  return apiFetch<BatchJob[]>("/api/v1/batch-jobs/archive");
}

export function archiveBatchJob(jobId: string) {
  return apiFetch<void>(`/api/v1/batch-jobs/${jobId}/archive`, { method: "POST" });
}

export function restoreBatchJob(jobId: string) {
  return apiFetch<void>(`/api/v1/batch-jobs/${jobId}/restore`, { method: "POST" });
}

export function getProjectStatistics(projectId: string) {
  return apiFetch<AnalysisStatistics>(`/api/v1/projects/${projectId}/statistics`);
}

export function getBatchStatistics(jobId: string) {
  return apiFetch<AnalysisStatistics>(`/api/v1/batch-jobs/${jobId}/statistics`);
}

export function listReports() {
  return apiFetch<ReportSummary[]>("/api/v1/reports");
}

export function getReport(reportId: string) {
  return apiFetch<ReportDetail>(`/api/v1/reports/${reportId}`);
}

export function createReport(input: { projectId?: string; batchJobId?: string; title?: string }) {
  return apiFetch<ReportDetail>("/api/v1/reports", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function regenerateReport(reportId: string) {
  return apiFetch<ReportDetail>(`/api/v1/reports/${reportId}/versions`, { method: "POST" });
}

export function confirmReport(reportId: string) {
  return apiFetch<ReportDetail>(`/api/v1/reports/${reportId}/confirm`, { method: "POST" });
}

export function getUserSettings() {
  return apiFetch<UserSetting>("/api/v1/user/settings");
}

export function updateUserSettings(update: Partial<Pick<UserSetting, "locale" | "reducedMotion" | "externalImageOptIn">>) {
  return apiFetch<UserSetting>("/api/v1/user/settings", {
    method: "PATCH",
    body: JSON.stringify(update),
  });
}

export function createBatchJob(
  images: File[],
  questions: string[],
  projectId: string | null,
  onProgress: (progress: number) => void,
): Promise<BatchJob> {
  return createBatchJobWithCsrf(images, questions, projectId, onProgress);
}

async function createBatchJobWithCsrf(
  images: File[],
  questions: string[],
  projectId: string | null,
  onProgress: (progress: number) => void,
): Promise<BatchJob> {
  const csrf = await csrfToken();
  const body = new FormData();
  images.forEach((image) => body.append("images", image));
  questions.forEach((question) => body.append("questions", question));
  if (projectId) body.append("projectId", projectId);
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open("POST", "/api/v1/batch-jobs");
    request.withCredentials = true;
    request.setRequestHeader(csrf.headerName, csrf.token);
    request.timeout = 120_000;
    request.upload.addEventListener("progress", (event) => {
      if (event.lengthComputable) onProgress(Math.round(event.loaded * 100 / event.total));
    });
    request.addEventListener("load", () => {
      let response: BatchJob | ApiError | undefined;
      try {
        response = JSON.parse(request.responseText) as BatchJob | ApiError;
      } catch {
        reject(new Error(`批量任务创建失败（${request.status || "网络错误"}）`));
        return;
      }
      if (request.status >= 200 && request.status < 300) {
        onProgress(100);
        resolve(response as BatchJob);
      } else {
        reject(new Error((response as ApiError).message || `批量任务创建失败（${request.status}）`));
      }
    });
    request.addEventListener("error", () => reject(new Error("网络连接中断，批量任务未创建。")));
    request.addEventListener("timeout", () => reject(new Error("批量任务上传超时，请缩小任务后重试。")));
    request.send(body);
  });
}

export function runTrustedAgent(
  message: string,
  projectId?: string,
  conversationId?: string,
  batchJobId?: string,
  toolName?: string,
  signal?: AbortSignal,
) {
  return apiFetch<AgentRun>("/api/v1/agent/runs", {
    method: "POST",
    body: JSON.stringify({ message, projectId, conversationId, batchJobId, toolName }),
    signal,
  });
}

export async function runTrustedAgentStream(
  request: {
    message: string;
    sessionId?: string;
    projectId?: string;
    conversationId?: string;
    batchJobId?: string;
    toolName?: string;
  },
  onState: (event: string) => void,
  signal?: AbortSignal,
): Promise<AgentRun> {
  const csrf = await csrfToken(signal);
  const response = await fetch("/api/v1/agent/runs/stream", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify(request),
    signal,
  });
  if (!response.ok || !response.body) {
    let messageText = `Agent 流式请求失败（${response.status}）`;
    try {
      messageText = ((await response.json()) as ApiError).message || messageText;
    } catch {
      // Keep the sanitized status message.
    }
    throw new Error(messageText);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let completed: AgentRun | undefined;
  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() ?? "";
    for (const block of blocks) {
      const event = block.split(/\r?\n/).find((line) => line.startsWith("event:"))?.slice(6).trim() ?? "message";
      const data = block.split(/\r?\n/).filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trim()).join("");
      onState(event);
      if (event === "completed") completed = JSON.parse(data) as AgentRun;
      if (event === "failed") throw new Error((JSON.parse(data) as { message?: string }).message ?? "Agent 调用失败。");
    }
    if (done) break;
  }
  if (!completed) throw new Error("Agent 流在返回完整结果前结束。");
  return completed;
}

export function listAgentSessions() {
  return apiFetch<AgentSessionSummary[]>("/api/v1/agent/sessions");
}

export function getAgentSession(sessionId: string) {
  return apiFetch<AgentSession>(`/api/v1/agent/sessions/${sessionId}`);
}

export function createAgentSession(input: {
  title?: string;
  projectId?: string;
  conversationId?: string;
  batchJobId?: string;
}) {
  return apiFetch<AgentSession>("/api/v1/agent/sessions", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function archiveAgentSession(sessionId: string) {
  return apiFetch<void>(`/api/v1/agent/sessions/${sessionId}`, { method: "DELETE" });
}

export function listAgentActions(sessionId?: string) {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : "";
  return apiFetch<AgentActionProposal[]>(`/api/v1/agent/actions${query}`);
}

export function proposeAgentAction(input: {
  sessionId?: string;
  actionName: AgentActionName;
  projectId?: string;
  conversationId?: string;
  batchJobId?: string;
  reportId?: string;
  questions?: string[];
  title?: string;
  format?: "md" | "markdown" | "json";
  modelReleaseId?: string;
}) {
  return apiFetch<AgentActionProposal>("/api/v1/agent/actions", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function confirmAgentAction(proposalId: string) {
  return apiFetch<AgentActionProposal>(`/api/v1/agent/actions/${proposalId}/confirm`, { method: "POST" });
}

export function rejectAgentAction(proposalId: string) {
  return apiFetch<AgentActionProposal>(`/api/v1/agent/actions/${proposalId}/reject`, { method: "POST" });
}

export function listKnowledgeDocuments() {
  return apiFetch<KnowledgeDocument[]>("/api/v1/knowledge/documents");
}

export function uploadKnowledgeDocument(document: File) {
  const body = new FormData();
  body.append("document", document);
  return apiFetch<KnowledgeDocument>("/api/v1/knowledge/documents", { method: "POST", body });
}

export function seedApprovedKnowledge() {
  return apiFetch<KnowledgeDocument>("/api/v1/knowledge/seed-approved-boundaries", { method: "POST" }, 100_000);
}

export function deleteKnowledgeDocument(documentId: string) {
  return apiFetch<void>(`/api/v1/knowledge/documents/${documentId}`, { method: "DELETE" });
}

export function searchKnowledge(query: string) {
  return apiFetch<KnowledgeSearchResult>("/api/v1/knowledge/search", {
    method: "POST",
    body: JSON.stringify({ query, topK: 5, threshold: 0.35 }),
  }, 100_000);
}

export function getSystemStatus() {
  return apiFetch<SystemStatus>("/api/v1/system/status");
}

export function listProviders() {
  return apiFetch<ProviderDescriptor[]>("/api/v1/providers");
}

export function listMyAuditEvents() {
  return apiFetch<AuditEvent[]>("/api/v1/audit/me");
}

export function logout() {
  return apiFetch<void>("/api/v1/auth/logout", { method: "POST" })
    .finally(() => {
      csrfTokenRequest = undefined;
    });
}
