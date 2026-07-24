import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BrowserRouter } from "react-router-dom";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BatchPage } from "./BatchPage";
import { KnowledgePage } from "./KnowledgePage";

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
    expect(screen.getByRole("button", { name: "创建批量任务" })).toBeDisabled();
    expect(screen.getByText("2", { selector: ".batch-summary strong" })).toBeInTheDocument();
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
});

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
