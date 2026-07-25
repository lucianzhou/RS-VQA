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
});

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
