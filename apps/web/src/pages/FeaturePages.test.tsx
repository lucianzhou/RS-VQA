import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BrowserRouter } from "react-router-dom";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BatchPage } from "./BatchPage";
import { KnowledgePage } from "./KnowledgePage";
import { ReportsPage } from "./ReportsPage";
import { SettingsPage } from "./SettingsPage";
import { AgentPage } from "./AgentPage";

function renderPage(page: ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <BrowserRouter>{page}</BrowserRouter>
    </QueryClientProvider>,
  );
}

describe("feature pages", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("keeps batch creation disabled until images are selected", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse([])));
    renderPage(<BatchPage />);
    expect(await screen.findByRole("heading", { name: "建立一组可复核的批量问答任务" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /上传图像/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "创建批量任务" })).toBeDisabled();
    expect(screen.getByText("2", { selector: ".batch-summary strong" })).toBeInTheDocument();
  });

  it("offers project, batch, and workspace contexts for a persistent Agent session", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/api/v1/projects")) {
        return jsonResponse([{ id: "project-1", name: "城市土地利用", conversations: [], updatedAt: new Date().toISOString() }]);
      }
      return jsonResponse([]);
    }));
    const user = userEvent.setup();
    renderPage(<AgentPage />);
    expect(await screen.findByRole("heading", { name: "分析会话" })).toBeInTheDocument();
    expect(screen.getByText("让分析建立在可核验事实之上")).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "项目" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "批量任务" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "整个工作区" })).toBeInTheDocument();
    await user.selectOptions(screen.getAllByRole("combobox")[0], "WORKSPACE");
    expect(screen.getByRole("button", { name: "新建分析会话" })).toBeEnabled();
  });

  it("shows indexed knowledge documents and exposes citation search", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse([{
      id: "doc-1",
      title: "已核准边界.md",
      sha256: "a".repeat(64),
      mimeType: "text/markdown",
      indexVersion: "rsvqa-knowledge-v1",
      status: "READY",
      errorMessage: null,
      createdAt: new Date().toISOString(),
    }])));
    const user = userEvent.setup();
    renderPage(<KnowledgePage />);
    expect(await screen.findByText("已核准边界.md")).toBeInTheDocument();
    const search = screen.getByLabelText("知识检索问题");
    await user.clear(search);
    await user.type(search, "模型边界");
    expect(screen.getByRole("button", { name: "检索" })).toBeEnabled();
  });

  it("accepts more than 32 images, paginates by twenty, and releases object URLs", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse([])));
    const createObjectURL = vi.mocked(URL.createObjectURL);
    const revokeObjectURL = vi.mocked(URL.revokeObjectURL);
    createObjectURL.mockImplementation((file) => `blob:${(file as File).name}`);
    const user = userEvent.setup();
    const view = renderPage(<BatchPage />);
    const fileInput = view.container.querySelector('input[type="file"]') as HTMLInputElement;
    const files = Array.from({ length: 40 }, (_, index) => (
      new File([`image-${index}`], `remote-${String(index + 1).padStart(2, "0")}.jpg`, {
        type: "image/jpeg",
        lastModified: index + 1,
      })
    ));

    await user.upload(fileInput, files);
    expect(screen.getByRole("button", { name: /添加图像/ })).toBeInTheDocument();
    expect(screen.getByText("已选择 40 / 200 张")).toBeInTheDocument();
    expect(screen.getByText("第 1 / 2 页 · 每页最多 20 张")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^查看大图/ })).toHaveLength(20);
    await user.click(screen.getByRole("button", { name: "下一页" }));
    expect(screen.getAllByRole("button", { name: /^查看大图/ })).toHaveLength(20);

    view.unmount();
    expect(revokeObjectURL).toHaveBeenCalledTimes(40);
  });

  it("renders a traceable deterministic report and its review queue", async () => {
    const facts = {
      scopeType: "PROJECT",
      scopeId: "project-1",
      scopeName: "城市土地利用",
      conversationCount: 2,
      imageCount: 2,
      questionCount: 4,
      answeredCount: 3,
      unsupportedCount: 1,
      failedCount: 0,
      lowConfidenceCount: 1,
      averageConfidence: 0.82,
      averageMargin: 0.4,
      questionTypeDistribution: { presence: 3, count: 1 },
      answerDistribution: { yes: 2, no: 1 },
      originDistribution: { mock_demo: 4 },
      confidenceDistribution: { high: 3, low: 1 },
      modelReleaseIds: ["mock-v1"],
      representativeCases: [],
      reviewCases: [{
        scopeItemId: "message-1",
        scopeLabel: "河流影像",
        question: "图中有没有道路？",
        answer: "yes",
        status: "COMPLETED",
        predictionOrigin: "mock_demo",
        modelReleaseId: "mock-v1",
        predictedQuestionType: "presence",
        confidence: 0.42,
        margin: 0.08,
        requestId: "request-1",
      }],
      calculationBoundary: "统计仅来自已持久化的模型调用。",
    };
    const report = {
      report: {
        id: "report-1",
        title: "城市土地利用分析报告",
        status: "DRAFT",
        reportType: "PROJECT_ANALYSIS",
        projectId: "project-1",
        batchJobId: null,
        currentVersion: 1,
        requestId: "request-report-1",
        confirmedAt: null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
      current: {
        id: "version-1",
        versionNumber: 1,
        factsJson: JSON.stringify(facts),
        markdownContent: "# 城市土地利用分析报告",
        agentSummary: null,
        citationsJson: null,
        modelReleaseId: "mock-v1",
        predictionOrigin: "deterministic_backend_statistics",
        generatedBy: "JAVA_ANALYTICS_SERVICE",
        createdAt: new Date().toISOString(),
      },
      versions: [],
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/api/v1/reports")) return jsonResponse([report.report]);
      if (path.endsWith("/api/v1/reports/report-1")) return jsonResponse(report);
      if (path.endsWith("/api/v1/projects")) return jsonResponse([{ id: "project-1", name: "城市土地利用" }]);
      if (path.endsWith("/api/v1/batch-jobs")) return jsonResponse([]);
      return jsonResponse({});
    }));

    renderPage(<ReportsPage />);
    expect(await screen.findByRole("heading", { name: "城市土地利用分析报告" })).toBeInTheDocument();
    expect(screen.getByText("河流影像")).toBeInTheDocument();
    expect(screen.getByText("统计仅来自已持久化的模型调用。")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Markdown" })).toHaveAttribute("href", "/api/v1/reports/report-1/export?format=md");
  });

  it("persists reduced-motion preference through the settings API", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/api/v1/user/settings")) {
        const update = init?.body ? JSON.parse(String(init.body)) as { reducedMotion?: boolean } : {};
        return jsonResponse({
          id: "setting-1",
          locale: "zh-CN",
          reducedMotion: update.reducedMotion ?? false,
          externalImageOptIn: false,
          externalImageBoundary: "默认不向外部服务发送图像。",
        });
      }
      if (path.endsWith("/api/v1/providers")) return jsonResponse([]);
      if (path.endsWith("/api/v1/system/status")) return jsonResponse({ status: "UP", version: "0.4.0", services: {} });
      return jsonResponse({});
    });
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    renderPage(<SettingsPage />);
    const motionSwitch = await screen.findByRole("switch", { name: "减少动态效果" });
    expect(motionSwitch).toHaveAttribute("aria-checked", "false");
    await user.click(motionSwitch);
    expect(await screen.findByRole("switch", { name: "减少动态效果" })).toHaveAttribute("aria-checked", "true");
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/user/settings",
      expect.objectContaining({ method: "PATCH", body: JSON.stringify({ reducedMotion: true }) }),
    );
  });

  it("shows the configured Gemini model in service status", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/api/v1/providers")) return jsonResponse([{
        providerId: "gemini",
        modelId: "gemini-2.5-flash",
        displayName: "gemini-2.5-flash",
        kind: "EXTERNAL_VLM",
        configurationState: "CONFIGURED",
        capabilities: ["vision"],
        vision: true,
        streaming: true,
        toolCalling: true,
        structuredOutput: true,
        timeout: "60s",
        maxRetries: 2,
        costMetadata: {},
      }]);
      if (path.endsWith("/api/v1/user/settings")) return jsonResponse({
        id: "setting-1",
        locale: "zh-CN",
        reducedMotion: false,
        externalImageOptIn: false,
        externalImageBoundary: "默认不向外部服务发送图像。",
      });
      if (path.endsWith("/api/v1/system/status")) return jsonResponse({ status: "UP", version: "0.4.0", services: {} });
      return jsonResponse({});
    }));

    const view = renderPage(<SettingsPage />);
    expect(await screen.findByText("gemini-2.5-flash · gemini-2.5-flash · 已配置")).toBeInTheDocument();
    expect(view.container.querySelector(".page-scroll > .settings-layout")).toBeInTheDocument();
    expect(view.container.querySelector(".page-scroll.settings-layout")).not.toBeInTheDocument();
  });
});

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
