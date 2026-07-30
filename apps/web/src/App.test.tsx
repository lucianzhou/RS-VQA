import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BrowserRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { useWorkspaceStore } from "./store";

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("App route transitions", () => {
  beforeEach(() => {
    window.history.replaceState({}, "", "/workspace");
    useWorkspaceStore.setState({
      sidebarCollapsed: false,
      sidebarOpen: false,
      signedOut: false,
    });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/api/v1/auth/demo-login")) {
        return jsonResponse({
          id: "user-1",
          username: "local-demo",
          displayName: "本地演示用户",
          role: "USER",
          demo: true,
        });
      }
      if (path.endsWith("/api/v1/user/settings")) {
        return jsonResponse({
          id: "setting-1",
          locale: "zh-CN",
          reducedMotion: false,
          externalImageOptIn: false,
          externalImageBoundary: "默认不向外部服务发送图像。",
        });
      }
      return jsonResponse([]);
    }));
  });

  it("renders the destination immediately without positional route motion", async () => {
    const user = userEvent.setup();
    const view = render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <BrowserRouter><App /></BrowserRouter>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole("link", { name: "批量 VQA" }));

    expect(await screen.findByRole("heading", { name: "建立一组可复核的批量问答任务" })).toBeInTheDocument();
    expect(view.container.querySelector(".route-frame")?.getAttribute("style") ?? "").not.toContain("transform");
  });
});
