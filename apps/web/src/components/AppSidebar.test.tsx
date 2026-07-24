import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BrowserRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useWorkspaceStore } from "../store";
import type { CurrentUser, Project } from "../types";
import { ModelSelector } from "./AppChrome";
import { AppSidebar } from "./AppSidebar";

const user: CurrentUser = {
  id: "user-1",
  username: "local-demo",
  displayName: "本地演示用户",
  role: "USER",
  demo: true,
};

const projects: Project[] = [
  {
    id: "project-forest",
    name: "森林调查",
    updatedAt: "2026-07-24T00:00:00Z",
    conversations: [
      { id: "conversation-road", title: "道路分布", hasImage: true, updatedAt: "2026-07-24T00:00:00Z" },
    ],
  },
  {
    id: "project-city",
    name: "城市土地利用",
    updatedAt: "2026-07-23T00:00:00Z",
    conversations: [
      { id: "conversation-building", title: "建筑物计数", hasImage: true, updatedAt: "2026-07-23T00:00:00Z" },
    ],
  },
];

function renderWithClient(node: React.ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <BrowserRouter>{node}</BrowserRouter>
    </QueryClientProvider>,
  );
}

describe("AppSidebar", () => {
  beforeEach(() => {
    localStorage.clear();
    useWorkspaceStore.setState({
      activeProjectId: "project-forest",
      activeConversationId: "conversation-road",
      sidebarOpen: false,
      sidebarCollapsed: false,
      signedOut: false,
    });
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(projects)));
  });

  it("filters projects and conversations as one search result set", async () => {
    const interaction = userEvent.setup();
    renderWithClient(<AppSidebar user={user} />);

    await screen.findByText("森林调查");
    await interaction.type(screen.getByLabelText("搜索项目与对话"), "建筑物");

    expect(screen.queryByText("森林调查")).not.toBeInTheDocument();
    expect(screen.getByText("城市土地利用")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "建筑物计数" })).toBeInTheDocument();
  });

  it("shows a clear empty search state", async () => {
    const interaction = userEvent.setup();
    renderWithClient(<AppSidebar user={user} />);

    await screen.findByText("森林调查");
    await interaction.type(screen.getByLabelText("搜索项目与对话"), "不存在的结果");

    expect(screen.getByText("抱歉，未查找到相关项目或对话。")).toBeInTheDocument();
    expect(screen.queryByText("森林调查")).not.toBeInTheDocument();
  });

  it("persists independent project expansion state", async () => {
    const interaction = userEvent.setup();
    renderWithClient(<AppSidebar user={user} />);

    const toggle = await screen.findByRole("button", { name: "森林调查" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    await interaction.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(JSON.parse(localStorage.getItem("rsvqa:expanded-projects") ?? "[]")).not.toContain("project-forest");
  });

  it("exposes the account menu from the full profile row", async () => {
    const interaction = userEvent.setup();
    renderWithClient(<AppSidebar user={user} />);
    await screen.findByText("森林调查");

    await interaction.click(screen.getByRole("button", { name: "打开账户菜单" }));
    expect(await screen.findByRole("menuitem", { name: /退出登录/ })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: /模型与 Provider/ })).toBeInTheDocument();
  });
});

describe("ModelSelector", () => {
  it("renders a branded listbox instead of a native select", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse([
      {
        providerId: "research-rsvqa",
        modelId: "mock-demo-not-a-research-release",
        displayName: "RS-VQA",
        kind: "RESEARCH_MODEL",
        configurationState: "CONFIGURED",
        capabilities: ["vision"],
        vision: true,
        streaming: false,
        toolCalling: false,
        structuredOutput: true,
        timeout: "30s",
        maxRetries: 0,
        costMetadata: {},
      },
      {
        providerId: "gemini",
        modelId: "none",
        displayName: "Gemini 通用视觉助手",
        kind: "EXTERNAL_VLM",
        configurationState: "UNCONFIGURED",
        capabilities: ["vision"],
        vision: true,
        streaming: true,
        toolCalling: true,
        structuredOutput: true,
        timeout: "45s",
        maxRetries: 1,
        costMetadata: {},
      },
    ])));
    const interaction = userEvent.setup();
    renderWithClient(<ModelSelector />);

    await interaction.click(await screen.findByRole("button", { name: /选择分析模式/ }));
    const listbox = await screen.findByRole("listbox", { name: "分析模式" });
    expect(within(listbox).getAllByRole("option")).toHaveLength(2);
    expect(document.querySelector("select")).not.toBeInTheDocument();
    expect(within(listbox).getByRole("option", { name: /Gemini/ })).toHaveAttribute("aria-disabled", "true");
  });
});

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
