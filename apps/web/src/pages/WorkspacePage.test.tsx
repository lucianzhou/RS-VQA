import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BrowserRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useWorkspaceStore } from "../store";
import type { ConversationDetail, ImageAsset } from "../types";
import { WorkspacePage } from "./WorkspacePage";

const asset: ImageAsset = {
  id: "image-1",
  originalName: "sample.jpg",
  sha256: "a".repeat(64),
  mimeType: "image/jpeg",
  sizeBytes: 512,
  width: 256,
  height: 256,
  contentUrl: "/api/v1/conversations/conversation-1/image/content",
};

function detail(image: ImageAsset | null = null): ConversationDetail {
  return {
    id: "conversation-1",
    projectId: "project-1",
    title: image ? "sample" : "新分析",
    image,
    messages: [],
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <BrowserRouter><WorkspacePage /></BrowserRouter>
    </QueryClientProvider>,
  );
}

describe("WorkspacePage", () => {
  beforeEach(() => {
    useWorkspaceStore.setState({
      activeProjectId: "project-1",
      activeConversationId: "conversation-1",
    });
    vi.restoreAllMocks();
  });

  it("starts with a focused image upload action", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(detail())));
    renderPage();
    expect(await screen.findByRole("heading", { name: "从一张遥感图像开始" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /选择图像/ })).toBeInTheDocument();
  });

  it("uploads a valid image and reveals the question composer", async () => {
    let uploaded = false;
    vi.stubGlobal("fetch", vi.fn(async (input, init) => {
      const url = String(input);
      if (url.endsWith("/image") && init?.method === "POST") {
        uploaded = true;
        return jsonResponse(asset);
      }
      return jsonResponse(detail(uploaded ? asset : null));
    }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("heading", { name: "从一张遥感图像开始" });
    const file = new File(["image"], "sample.jpg", { type: "image/jpeg" });
    await user.upload(screen.getByLabelText("选择遥感图像"), file);
    expect(await screen.findByText("sample.jpg")).toBeInTheDocument();
    expect(screen.getByPlaceholderText("问问这张遥感图像…")).toBeEnabled();
  });

  it("rejects non-image files before upload", async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse(detail()));
    vi.stubGlobal("fetch", fetchMock);
    renderPage();
    await screen.findByRole("heading", { name: "从一张遥感图像开始" });
    const file = new File(["text"], "notes.txt", { type: "text/plain" });
    fireEvent.change(screen.getByLabelText("选择遥感图像"), { target: { files: [file] } });
    expect(screen.getByRole("alert")).toHaveTextContent("请选择 PNG、JPG 或 WEBP 图像");
    expect(fetchMock.mock.calls.some(([input, init]) =>
      String(input).endsWith("/image") && (init as RequestInit | undefined)?.method === "POST"
    )).toBe(false);
  });

  it("marks a raw answer whose shape conflicts with the predicted question type for review", async () => {
    const conversation: ConversationDetail = {
      ...detail(asset),
      messages: [{
        id: "message-1",
        role: "assistant",
        sourceType: "RESEARCH_MODEL",
        content: "no",
        metadataJson: JSON.stringify({
          capabilityNotice: "当前答案形式与预测题型不一致，请人工复核；系统保留原始模型输出。",
          requiresReview: true,
        }),
        invocation: {
          id: "invocation-1",
          requestId: "request-1",
          status: "answered",
          predictionOrigin: "research_vilt_predicted_soft",
          modelReleaseId: "release-1",
          providerType: "RESEARCH_MODEL",
          providerModel: null,
          confidence: 0.65,
          margin: 0.35,
          latencyMs: 700,
          promptTokens: null,
          completionTokens: null,
          totalTokens: null,
          estimatedCostUsd: null,
        },
        createdAt: new Date().toISOString(),
      }],
    };
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(conversation)));

    renderPage();

    expect(await screen.findByText("答案形式异常，请复核")).toBeInTheDocument();
    expect(screen.getByText("no", { selector: ".answer-value" })).toBeInTheDocument();
    expect(screen.getByText(/系统保留原始模型输出/)).toBeInTheDocument();
  });

  it("shows how the question was understood without replacing the raw prediction", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(withAssistantMessage({
      content: "3",
      metadata: {
        capabilityNotice: "研究模型输出仅适用于 RSVQA-HR 已验证的闭集问题分布。",
        interpretationNote: "已理解为：图中有多少条道路？",
        displayAnswer: "3 条道路",
        canonicalQuestion: "What is the amount of roads?",
        modelInputQuestion: "What is the amount of roads?",
        normalizerVersion: "2.0.0",
        matchedIntent: "count",
        scopeVerification: "release_anchored",
      },
    }))));

    renderPage();

    expect(await screen.findByText("已理解为：图中有多少条道路？")).toBeInTheDocument();
    // The raw closed-set prediction stays the primary value.
    expect(screen.getByText("3", { selector: ".answer-value" })).toBeInTheDocument();
    expect(screen.getByText("3 条道路", { selector: ".answer-display" })).toBeInTheDocument();
  });

  it("keeps the raw prediction when no localized rendering is available", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(withAssistantMessage({
      content: "yes",
      metadata: { capabilityNotice: "notice" },
    }))));

    renderPage();

    expect(await screen.findByText("yes", { selector: ".answer-value" })).toBeInTheDocument();
    expect(document.querySelector(".answer-display")).toBeNull();
    expect(document.querySelector(".canonical-hint")).toBeNull();
  });

  it("offers clarification options that prefill the composer instead of answering", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(withAssistantMessage({
      content: "“住宅”可能指住宅建筑或住宅区，请明确说明后重试。",
      status: "unsupported",
      metadata: {
        capabilityNotice: "“住宅”可能指住宅建筑或住宅区，请明确说明后重试。",
        needsClarification: true,
        clarificationOptions: ["住宅建筑", "住宅区"],
        reasonCode: "ambiguous_object_alias",
        normalizerVersion: "2.0.0",
      },
    }))));
    const user = userEvent.setup();

    renderPage();

    expect(await screen.findByText("需要补充说明")).toBeInTheDocument();
    expect(document.querySelector(".answer-value")).toBeNull();
    await user.click(screen.getByRole("button", { name: "住宅区" }));
    expect(screen.getByPlaceholderText("问问这张遥感图像…")).toHaveValue("住宅区");
  });

  it("flags a provisional object and question-type pairing", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(withAssistantMessage({
      content: "2",
      metadata: { capabilityNotice: "notice", scopeVerification: "provisional" },
    }))));

    renderPage();

    expect(await screen.findByText("该地物与题型组合仍在核验中，结果仅供参考。")).toBeInTheDocument();
  });

  it("uses the selected provider identity while an image request is pending", async () => {
    useWorkspaceStore.setState({ selectedModelId: "qwen" });
    vi.stubGlobal("fetch", vi.fn(async (input, init) => {
      if (init?.method === "POST" && String(input).includes("/questions")) {
        return new Promise<Response>(() => {});
      }
      return jsonResponse(detail(asset));
    }));
    const user = userEvent.setup();
    renderPage();

    const composer = await screen.findByPlaceholderText("问问这张遥感图像…");
    await user.type(composer, "建筑物比道路多吗？");
    await user.click(screen.getByRole("button", { name: "发送问题" }));

    expect(await screen.findByText("Qwen3-VL 32B 正在分析当前影像…")).toBeInTheDocument();
    expect(document.querySelector(".pending-answer")?.previousElementSibling).toHaveClass("qwen");
  });

  it("renders external model Markdown without changing research predictions", async () => {
    const conversation: ConversationDetail = {
      ...detail(asset),
      messages: [{
        id: "message-external",
        role: "assistant",
        sourceType: "EXTERNAL_VLM",
        content: "### 分析结论\n\n**建筑物**多于道路。\n\n- 建筑密集\n- 道路呈网格状",
        metadataJson: JSON.stringify({ providerId: "gemini" }),
        invocation: {
          id: "invocation-external",
          requestId: "request-external",
          status: "answered",
          predictionOrigin: "external_vlm_assist",
          modelReleaseId: null,
          providerType: "EXTERNAL_VLM",
          providerModel: "gemini-3.6-flash",
          confidence: null,
          margin: null,
          latencyMs: 1200,
          promptTokens: 20,
          completionTokens: 30,
          totalTokens: 50,
          estimatedCostUsd: null,
        },
        createdAt: new Date().toISOString(),
      }],
    };
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(conversation)));
    const { container } = renderPage();

    expect(await screen.findByRole("heading", { level: 3, name: "分析结论" })).toBeInTheDocument();
    expect(screen.getByText("建筑物").tagName).toBe("STRONG");
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(container.textContent).not.toContain("###");
    expect(container.textContent).not.toContain("**");
    expect(container.querySelector(".answer-value")).toBeNull();
  });
});

function withAssistantMessage(options: {
  content: string;
  metadata: Record<string, unknown>;
  status?: string;
}): ConversationDetail {
  return {
    ...detail(asset),
    messages: [{
      id: "message-1",
      role: "assistant",
      sourceType: "RESEARCH_MODEL",
      content: options.content,
      metadataJson: JSON.stringify(options.metadata),
      invocation: {
        id: "invocation-1",
        requestId: "request-1",
        status: options.status ?? "answered",
        predictionOrigin: "research_vilt_predicted_soft",
        modelReleaseId: "release-1",
        providerType: "RESEARCH_MODEL",
        providerModel: null,
        confidence: 0.88,
        margin: 0.4,
        latencyMs: 700,
        promptTokens: null,
        completionTokens: null,
        totalTokens: null,
        estimatedCostUsd: null,
      },
      createdAt: new Date().toISOString(),
    }],
  };
}

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
