import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, within } from "@testing-library/react";
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

function renderWithClient(node: React.ReactNode, client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return render(
    <QueryClientProvider client={client}>
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

  it("collapses an empty project composer when clicking outside", async () => {
    const interaction = userEvent.setup();
    renderWithClient(<AppSidebar user={user} />);
    await screen.findByText("森林调查");

    await interaction.click(screen.getByRole("button", { name: "新建项目" }));
    expect(screen.getByRole("textbox", { name: "项目名称" })).toBeInTheDocument();
    await interaction.click(screen.getByRole("textbox", { name: "搜索项目与对话" }));
    expect(screen.queryByRole("textbox", { name: "项目名称" })).not.toBeInTheDocument();
  });

  it("collapses the project composer with Escape", async () => {
    const interaction = userEvent.setup();
    renderWithClient(<AppSidebar user={user} />);
    await screen.findByText("森林调查");

    await interaction.click(screen.getByRole("button", { name: "新建项目" }));
    await interaction.keyboard("{Escape}");
    expect(screen.queryByRole("textbox", { name: "项目名称" })).not.toBeInTheDocument();
  });

  it("keeps a cached active conversation when the project list is temporarily stale", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(["conversation", "conversation-new"], {
      id: "conversation-new",
      projectId: "project-forest",
      title: "新分析",
      image: null,
      messages: [],
    });
    useWorkspaceStore.setState({
      activeProjectId: "project-forest",
      activeConversationId: "conversation-new",
    });

    renderWithClient(<AppSidebar user={user} />, client);
    await screen.findByText("森林调查");

    expect(useWorkspaceStore.getState().activeConversationId).toBe("conversation-new");
    expect(useWorkspaceStore.getState().activeProjectId).toBe("project-forest");
  });

  it("enters a neutral state until a newly created conversation becomes active", async () => {
    let finishCreate: ((value: Response) => void) | undefined;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/project-forest/conversations") && init?.method === "POST") {
        return new Promise<Response>((resolve) => {
          finishCreate = resolve;
        });
      }
      return jsonResponse(projects);
    }));
    const interaction = userEvent.setup();
    renderWithClient(<AppSidebar user={user} />);
    await screen.findByText("森林调查");

    await interaction.click(screen.getByRole("button", { name: "新建分析" }));
    expect(useWorkspaceStore.getState().activeConversationId).toBeNull();

    await act(async () => {
      finishCreate?.(jsonResponse({
        id: "conversation-new",
        projectId: "project-forest",
        title: "新分析",
        image: null,
        messages: [],
        createdAt: "2026-07-27T00:00:00Z",
        updatedAt: "2026-07-27T00:00:00Z",
      }));
    });

    await vi.waitFor(() => {
      expect(useWorkspaceStore.getState().activeConversationId).toBe("conversation-new");
    });
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
    expect(within(listbox).queryByText("Gemini 通用视觉助手")).toBeNull();
    expect(within(listbox).queryByText("外部通用视觉模型")).toBeNull();
  });
});

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
