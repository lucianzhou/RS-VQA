import {
  Archive,
  BookOpen,
  Check,
  ChevronDown,
  ChevronRight,
  CircleUserRound,
  FileClock,
  Folder,
  Layers3,
  Menu,
  MessageSquareText,
  PanelLeftClose,
  Plus,
  Search,
  Settings,
  ShieldCheck,
  Sparkles,
  X,
} from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { NavLink, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { createConversation, createProject, demoLogin, listProjects, listProviders } from "./api";
import { BatchPage } from "./pages/BatchPage";
import { SettingsPage } from "./pages/SettingsPage";
import { KnowledgePage } from "./pages/KnowledgePage";
import { AuditPage } from "./pages/AuditPage";
import { WorkspacePage } from "./pages/WorkspacePage";
import { useWorkspaceStore } from "./store";
import type { ModelOption } from "./types";

export function App() {
  const sidebarCollapsed = useWorkspaceStore((state) => state.sidebarCollapsed);
  const session = useQuery({
    queryKey: ["session"],
    queryFn: demoLogin,
    retry: 1,
    staleTime: Infinity,
  });
  if (session.isPending) {
    return <BootstrapState title="正在准备工作区" detail="建立本地演示会话…" />;
  }
  if (session.isError) {
    return <BootstrapState title="无法连接业务服务" detail={session.error.message} error />;
  }
  return (
    <div className={`app-shell ${sidebarCollapsed ? "sidebar-collapsed" : ""}`}>
      <Sidebar />
      <div className="app-content">
        <Routes>
          <Route path="/" element={<Navigate to="/workspace" replace />} />
          <Route path="/workspace" element={<WorkspacePage />} />
          <Route path="/batch" element={<BatchPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/knowledge" element={<KnowledgePage />} />
          <Route path="/audit" element={<AuditPage />} />
          <Route path="*" element={<Navigate to="/workspace" replace />} />
        </Routes>
      </div>
    </div>
  );
}

function Sidebar() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const projectsQuery = useQuery({ queryKey: ["projects"], queryFn: listProjects });
  const projects = projectsQuery.data ?? [];
  const activeProjectId = useWorkspaceStore((state) => state.activeProjectId);
  const activeConversationId = useWorkspaceStore((state) => state.activeConversationId);
  const sidebarOpen = useWorkspaceStore((state) => state.sidebarOpen);
  const setSidebarOpen = useWorkspaceStore((state) => state.setSidebarOpen);
  const sidebarCollapsed = useWorkspaceStore((state) => state.sidebarCollapsed);
  const setSidebarCollapsed = useWorkspaceStore((state) => state.setSidebarCollapsed);
  const setActiveConversation = useWorkspaceStore((state) => state.setActiveConversation);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState("");
  const [projectComposerOpen, setProjectComposerOpen] = useState(false);
  const [projectName, setProjectName] = useState("");
  const createMutation = useMutation({
    mutationFn: async () => {
      let projectId = activeProjectId ?? projects[0]?.id;
      if (!projectId) {
        projectId = (await createProject("城市土地利用")).id;
      }
      return createConversation(projectId);
    },
    onSuccess: async (conversation) => {
      setActiveConversation(conversation.projectId, conversation.id);
      await queryClient.invalidateQueries({ queryKey: ["projects"] });
      queryClient.setQueryData(["conversation", conversation.id], conversation);
      navigate("/workspace");
    },
  });
  const createProjectMutation = useMutation({
    mutationFn: async () => {
      const project = await createProject(projectName.trim());
      const conversation = await createConversation(project.id);
      return { project, conversation };
    },
    onSuccess: async ({ project, conversation }) => {
      setProjectName("");
      setProjectComposerOpen(false);
      setActiveConversation(project.id, conversation.id);
      await queryClient.invalidateQueries({ queryKey: ["projects"] });
      queryClient.setQueryData(["conversation", conversation.id], conversation);
      navigate("/workspace");
    },
  });

  useEffect(() => {
    if (activeConversationId || projects.length === 0) return;
    const firstProject = projects[0];
    const firstConversation = firstProject.conversations[0];
    if (firstConversation) setActiveConversation(firstProject.id, firstConversation.id);
  }, [activeConversationId, projects, setActiveConversation]);

  return (
    <>
      <button className="mobile-menu-button icon-button" type="button" aria-label="打开导航" onClick={() => {
        if (sidebarCollapsed) setSidebarCollapsed(false);
        else setSidebarOpen(true);
      }}>
        <Menu size={20} />
      </button>
      {sidebarOpen && <button className="sidebar-scrim" type="button" aria-label="关闭导航" onClick={() => setSidebarOpen(false)} />}
      <aside className={`sidebar ${sidebarOpen ? "is-open" : ""}`} aria-label="RS-VQA 导航侧栏">
        <div className="brand-row">
          <NavLink className="brand" to="/workspace" onClick={() => setSidebarOpen(false)}>
            <span className="brand-mark" aria-hidden="true">RS</span>
            <span>RS-VQA</span>
          </NavLink>
          <button className="icon-button sidebar-close" type="button" aria-label="收起导航" onClick={() => setSidebarCollapsed(true)}><PanelLeftClose size={18} /></button>
          <button className="icon-button mobile-sidebar-close" type="button" aria-label="关闭导航" onClick={() => setSidebarOpen(false)}><X size={20} /></button>
        </div>

        <button className="new-analysis-button" type="button" disabled={createMutation.isPending} onClick={() => createMutation.mutate()}>
          <Plus size={17} />{createMutation.isPending ? "正在创建…" : "新建分析"}
        </button>
        {searchOpen ? <label className="sidebar-search-input"><Search size={15} /><input autoFocus aria-label="搜索对话" value={searchTerm} onChange={(event) => setSearchTerm(event.target.value)} placeholder="搜索对话…" /><button type="button" aria-label="关闭搜索" onClick={() => { setSearchOpen(false); setSearchTerm(""); }}><X size={14} /></button></label>
          : <button className="search-button" type="button" onClick={() => setSearchOpen(true)}><Search size={16} /><span>搜索对话</span><kbd>⌘ K</kbd></button>}

        <nav className="sidebar-scroll" aria-label="主导航">
          <div className="nav-section-heading">
            <p className="nav-section-label">项目</p>
            <button className="icon-button" type="button" aria-label="新建项目" onClick={() => setProjectComposerOpen((open) => !open)}><Plus size={13} /></button>
          </div>
          {projectComposerOpen && (
            <form className="project-composer" onSubmit={(event) => {
              event.preventDefault();
              if (projectName.trim()) createProjectMutation.mutate();
            }}>
              <input autoFocus aria-label="项目名称" maxLength={160} value={projectName} onChange={(event) => setProjectName(event.target.value)} placeholder="项目名称" />
              <button type="submit" aria-label="确认新建项目" disabled={!projectName.trim() || createProjectMutation.isPending}><Check size={14} /></button>
            </form>
          )}
          {projects.map((project) => (
            <section className="project-group" key={project.id}>
              <div className="project-heading">
                <ChevronDown size={14} /><Folder size={15} /><span>{project.name}</span>
                <button className="icon-button project-add" type="button" aria-label={`在${project.name}中新建分析`} onClick={async () => {
                  const conversation = await createConversation(project.id);
                  setActiveConversation(project.id, conversation.id);
                  await queryClient.invalidateQueries({ queryKey: ["projects"] });
                  queryClient.setQueryData(["conversation", conversation.id], conversation);
                  navigate("/workspace");
                }}><Plus size={14} /></button>
              </div>
              {project.conversations.filter((conversation) => conversation.title.toLowerCase().includes(searchTerm.trim().toLowerCase())).map((conversation) => (
                <button
                  className={`conversation-link ${conversation.id === activeConversationId ? "is-active" : ""}`}
                  type="button"
                  key={conversation.id}
                  onClick={() => {
                    setActiveConversation(project.id, conversation.id);
                    navigate("/workspace");
                  }}
                >
                  <MessageSquareText size={15} /><span>{conversation.title}</span>
                </button>
              ))}
              {project.conversations.length === 0 && <p className="empty-project">还没有对话</p>}
            </section>
          ))}

          <p className="nav-section-label nav-secondary-label">工作区</p>
          <NavItem to="/batch" icon={<Layers3 size={16} />} label="批量 VQA" />
          <button className="nav-link" type="button" disabled><FileClock size={16} /><span>分析报告</span><small>规划中</small></button>
          <NavItem to="/knowledge" icon={<BookOpen size={16} />} label="知识库" />
          <NavItem to="/audit" icon={<ShieldCheck size={16} />} label="调用审计" />
          <button className="nav-link" type="button" disabled><Archive size={16} /><span>归档</span><small>规划中</small></button>
        </nav>

        <div className="sidebar-footer">
          <NavItem to="/settings" icon={<Settings size={16} />} label="模型与设置" />
          <div className="profile-row">
            <CircleUserRound size={28} strokeWidth={1.5} />
            <div><strong>本地演示用户</strong><span>Demo workspace</span></div>
            <ChevronRight size={15} />
          </div>
        </div>
      </aside>
    </>
  );
}

function BootstrapState({ title, detail, error = false }: { title: string; detail: string; error?: boolean }) {
  return (
    <main className={`bootstrap-state ${error ? "is-error" : ""}`}>
      <span className="brand-mark" aria-hidden="true">RS</span>
      <h1>{title}</h1>
      <p>{detail}</p>
    </main>
  );
}

function NavItem({ to, icon, label }: { to: string; icon: ReactNode; label: string }) {
  const setSidebarOpen = useWorkspaceStore((state) => state.setSidebarOpen);
  return <NavLink className={({ isActive }) => `nav-link ${isActive ? "is-active" : ""}`} to={to} onClick={() => setSidebarOpen(false)}>{icon}<span>{label}</span></NavLink>;
}

export function AppTopbar({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <header className="topbar">
      <div className="topbar-title"><h1>{title}</h1>{subtitle && <span>{subtitle}</span>}</div>
      <div className="topbar-actions">{actions}</div>
    </header>
  );
}

export const modelOptions: ModelOption[] = [
  {
    id: "research-rsvqa",
    name: "RS-VQA 研究协议（Mock）",
    description: "qdrop15 · predicted-soft · 非研究结果",
    kind: "MOCK",
    configured: true,
    releaseId: "mock-demo-not-a-research-release",
  },
  {
    id: "external-vlm",
    name: "外部通用视觉模型",
    description: "用于开放式辅助分析",
    kind: "EXTERNAL_VLM",
    configured: false,
  },
];

export function ModelSelector() {
  const providers = useQuery({ queryKey: ["providers"], queryFn: listProviders, staleTime: 10_000 });
  const options = Array.isArray(providers.data) ? providers.data.map(providerToModelOption) : modelOptions;
  const selectedModelId = useWorkspaceStore((state) => state.selectedModelId);
  const setSelectedModelId = useWorkspaceStore((state) => state.setSelectedModelId);
  const selected = options.find((model) => model.id === selectedModelId) ?? options[0];
  return (
    <label className="model-selector">
      <ShieldCheck size={16} />
      <span><strong>{selected.name}</strong><small>{selected.configured ? selected.description : "未配置"}</small></span>
      <select aria-label="选择模型" value={selectedModelId} onChange={(event) => setSelectedModelId(event.target.value)}>
        {options.map((model) => <option key={model.id} value={model.id} disabled={!model.configured}>{model.name}{model.configured ? "" : "（未配置）"}</option>)}
      </select>
      <ChevronDown size={14} />
    </label>
  );
}

export function providerToModelOption(provider: import("./types").ProviderDescriptor): ModelOption {
  const isMock = provider.kind === "RESEARCH_MODEL" && provider.modelId.startsWith("mock-");
  return {
    id: provider.providerId,
    name: isMock ? "RS-VQA 研究协议（Mock）" : provider.displayName,
    description: isMock
      ? "qdrop15 · predicted-soft · 非研究结果"
      : provider.kind === "RESEARCH_MODEL"
        ? "RSVQA-HR · qdrop15 · predicted-soft"
        : "用于开放式辅助分析，不属于论文模型输出",
    kind: isMock ? "MOCK" : provider.kind,
    configured: provider.configurationState === "CONFIGURED",
    releaseId: provider.modelId === "none" ? undefined : provider.modelId,
  };
}

export function StatusBadge({ children, tone = "neutral" }: { children: ReactNode; tone?: "neutral" | "success" | "warning" }) {
  return <span className={`status-badge ${tone}`}><Sparkles size={12} />{children}</span>;
}
