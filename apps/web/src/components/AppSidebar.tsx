import * as Dialog from "@radix-ui/react-dialog";
import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Archive,
  BookOpen,
  Bot,
  Check,
  ChevronDown,
  ChevronRight,
  CircleUserRound,
  FileClock,
  Folder,
  Layers3,
  LogOut,
  Menu,
  MessageSquareText,
  MoreHorizontal,
  PanelLeftClose,
  Pencil,
  Plus,
  Search,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  UserRound,
  X,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import {
  archiveConversation,
  archiveProject,
  createConversation,
  createProject,
  listProjects,
  logout,
  renameProject,
  restoreConversation,
  restoreProject,
  updateConversation,
} from "../api";
import { useWorkspaceStore } from "../store";
import { BrandMark } from "./BrandMark";
import type { CurrentUser, Project } from "../types";

type Action =
  | { kind: "rename-project"; id: string; value: string }
  | { kind: "rename-conversation"; id: string; value: string }
  | { kind: "move-conversation"; id: string; value: string; projectId: string }
  | { kind: "archive-project"; id: string; value: string }
  | { kind: "archive-conversation"; id: string; value: string }
  | null;

interface UndoToast {
  message: string;
  undo: () => void;
}

export function AppSidebar({ user }: { user: CurrentUser }) {
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
  const clearActiveConversation = useWorkspaceStore((state) => state.clearActiveConversation);
  const setSignedOut = useWorkspaceStore((state) => state.setSignedOut);
  const searchRef = useRef<HTMLInputElement>(null);
  const projectCreateAreaRef = useRef<HTMLDivElement>(null);
  const [searchTerm, setSearchTerm] = useState("");
  const [projectComposerOpen, setProjectComposerOpen] = useState(false);
  const [projectName, setProjectName] = useState("");
  const [action, setAction] = useState<Action>(null);
  const [actionValue, setActionValue] = useState("");
  const [toast, setToast] = useState<UndoToast | null>(null);
  const [expandedProjects, setExpandedProjects] = useState<Set<string>>(() => {
    try {
      return new Set(JSON.parse(localStorage.getItem("rsvqa:expanded-projects") ?? "[]") as string[]);
    } catch {
      return new Set();
    }
  });

  const normalizedSearch = searchTerm.trim().toLocaleLowerCase();
  const visibleProjects = useMemo(() => projects.flatMap((project) => {
    if (!normalizedSearch) return [project];
    const projectMatches = project.name.toLocaleLowerCase().includes(normalizedSearch);
    const conversations = projectMatches
      ? project.conversations
      : project.conversations.filter((conversation) => conversation.title.toLocaleLowerCase().includes(normalizedSearch));
    return projectMatches || conversations.length > 0 ? [{ ...project, conversations }] : [];
  }), [normalizedSearch, projects]);

  const refreshProjects = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["projects"] }),
      queryClient.invalidateQueries({ queryKey: ["archive"] }),
    ]);
  };

  const createMutation = useMutation({
    mutationFn: async () => {
      let projectId = activeProjectId ?? projects[0]?.id;
      if (!projectId) projectId = (await createProject("城市土地利用")).id;
      return createConversation(projectId);
    },
    onSuccess: async (conversation) => {
      setExpandedProjects((current) => new Set(current).add(conversation.projectId));
      await refreshProjects();
      queryClient.setQueryData(["conversation", conversation.id], conversation);
      setActiveConversation(conversation.projectId, conversation.id);
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
      setExpandedProjects((current) => new Set(current).add(project.id));
      await refreshProjects();
      queryClient.setQueryData(["conversation", conversation.id], conversation);
      setActiveConversation(project.id, conversation.id);
      navigate("/workspace");
    },
  });

  const actionMutation = useMutation({
    mutationFn: async (current: Exclude<Action, null>) => {
      if (current.kind === "rename-project") return renameProject(current.id, actionValue.trim());
      if (current.kind === "rename-conversation") return updateConversation(current.id, { title: actionValue.trim() });
      if (current.kind === "move-conversation") return updateConversation(current.id, { projectId: actionValue });
      if (current.kind === "archive-project") return archiveProject(current.id);
      return archiveConversation(current.id);
    },
    onSuccess: async (_, current) => {
      const isArchive = current.kind === "archive-project" || current.kind === "archive-conversation";
      if (isArchive) {
        const undo = current.kind === "archive-project"
          ? () => restoreProject(current.id).then(refreshProjects)
          : () => restoreConversation(current.id).then(refreshProjects);
        setToast({ message: `“${current.value}”已归档`, undo });
      }
      if (
        (current.kind === "archive-conversation" && current.id === activeConversationId)
        || (current.kind === "archive-project" && current.id === activeProjectId)
      ) {
        const next = projects
          .filter((project) => current.kind !== "archive-project" || project.id !== current.id)
          .flatMap((project) => project.conversations.map((conversation) => ({ project, conversation })))
          .find(({ conversation }) => current.kind !== "archive-conversation" || conversation.id !== current.id);
        if (next) setActiveConversation(next.project.id, next.conversation.id);
        else clearActiveConversation();
      }
      setAction(null);
      await refreshProjects();
    },
  });

  const logoutMutation = useMutation({
    mutationFn: logout,
    onSuccess: () => {
      queryClient.clear();
      clearActiveConversation();
      setSignedOut(true);
    },
  });

  useEffect(() => {
    if (activeProjectId) {
      setExpandedProjects((current) => {
        if (current.has(activeProjectId)) return current;
        return new Set(current).add(activeProjectId);
      });
    }
  }, [activeProjectId]);

  useEffect(() => {
    localStorage.setItem("rsvqa:expanded-projects", JSON.stringify([...expandedProjects]));
  }, [expandedProjects]);

  useEffect(() => {
    if (activeConversationId || projects.length === 0) return;
    const first = projects.flatMap((project) => project.conversations.map((conversation) => ({ project, conversation })))[0];
    if (first) setActiveConversation(first.project.id, first.conversation.id);
  }, [activeConversationId, projects, setActiveConversation]);

  useEffect(() => {
    if (!activeConversationId || projects.length === 0) return;
    const active = projects.some((project) => project.id === activeProjectId
      && project.conversations.some((conversation) => conversation.id === activeConversationId));
    if (active) return;
    const first = projects.flatMap((project) => project.conversations.map((conversation) => ({ project, conversation })))[0];
    if (first) setActiveConversation(first.project.id, first.conversation.id);
    else clearActiveConversation();
  }, [activeConversationId, activeProjectId, clearActiveConversation, projects, setActiveConversation]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        if (sidebarCollapsed) setSidebarCollapsed(false);
        window.requestAnimationFrame(() => searchRef.current?.focus());
      }
      if (event.key === "Escape" && document.activeElement === searchRef.current) {
        setSearchTerm("");
        searchRef.current?.blur();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [setSidebarCollapsed, sidebarCollapsed]);

  useEffect(() => {
    if (!projectComposerOpen) return;
    const onPointerDown = (event: PointerEvent) => {
      if (projectCreateAreaRef.current?.contains(event.target as Node)) return;
      if (!projectName.trim()) setProjectComposerOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setProjectComposerOpen(false);
        if (!projectName.trim()) setProjectName("");
      }
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [projectComposerOpen, projectName]);

  const openAction = (next: Exclude<Action, null>) => {
    setAction(next);
    setActionValue(next.kind === "move-conversation" ? next.projectId : next.value);
  };

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
            <BrandMark />
            <span>RS-VQA</span>
          </NavLink>
          <button className="icon-button sidebar-close" type="button" aria-label="收起导航" onClick={() => setSidebarCollapsed(true)}><PanelLeftClose size={18} /></button>
          <button className="icon-button mobile-sidebar-close" type="button" aria-label="关闭导航" onClick={() => setSidebarOpen(false)}><X size={20} /></button>
        </div>

        <button className="new-analysis-button" type="button" disabled={createMutation.isPending} onClick={() => createMutation.mutate()}>
          <Plus size={17} />{createMutation.isPending ? "正在创建…" : "新建分析"}
        </button>

        <label className="sidebar-search-input">
          <Search size={15} aria-hidden="true" />
          <input ref={searchRef} aria-label="搜索项目与对话" name="workspace-search" autoComplete="off" value={searchTerm} onChange={(event) => setSearchTerm(event.target.value)} placeholder="搜索项目与对话…" />
          {searchTerm ? <button type="button" aria-label="清空搜索" onClick={() => setSearchTerm("")}><X size={14} /></button> : <kbd>⌘ K</kbd>}
        </label>

        <nav className="sidebar-scroll" aria-label="主导航">
          <div className="project-create-area" ref={projectCreateAreaRef}>
            {projectComposerOpen ? (
              <form className="project-composer-inline" onSubmit={(event) => {
                event.preventDefault();
                if (projectName.trim()) createProjectMutation.mutate();
              }}>
                <input autoFocus aria-label="项目名称" name="project-name" autoComplete="off" maxLength={160} value={projectName} onChange={(event) => setProjectName(event.target.value)} placeholder="输入项目名称…" />
                <button type="submit" aria-label="确认新建项目" disabled={!projectName.trim() || createProjectMutation.isPending}><Check size={14} /></button>
              </form>
            ) : (
              <div className="nav-section-heading">
                <p className="nav-section-label">项目</p>
                <button className="icon-button" type="button" aria-label="新建项目" onClick={() => setProjectComposerOpen(true)}><Plus size={16} /></button>
              </div>
            )}
          </div>

          {visibleProjects.map((project) => {
            const expanded = normalizedSearch ? true : expandedProjects.has(project.id);
            return (
              <section className="project-group" key={project.id}>
                <div className="project-heading">
                  <button className="project-toggle" type="button" aria-expanded={expanded} onClick={() => setExpandedProjects((current) => {
                    const next = new Set(current);
                    if (next.has(project.id)) next.delete(project.id);
                    else next.add(project.id);
                    return next;
                  })}>
                    <ChevronDown className={expanded ? "is-expanded" : ""} size={14} />
                    <Folder size={15} />
                    <span><Highlight text={project.name} query={searchTerm} /></span>
                  </button>
                  <ProjectMenu project={project} onCreate={async () => {
                    const conversation = await createConversation(project.id);
                    setExpandedProjects((current) => new Set(current).add(project.id));
                    setActiveConversation(project.id, conversation.id);
                    await refreshProjects();
                    queryClient.setQueryData(["conversation", conversation.id], conversation);
                    navigate("/workspace");
                  }} onAction={openAction} />
                </div>
                <div className={`project-conversations ${expanded ? "is-expanded" : ""}`} inert={!expanded ? true : undefined}>
                  <div>
                    {project.conversations.map((conversation) => (
                      <div className={`conversation-row ${conversation.id === activeConversationId ? "is-active" : ""}`} key={conversation.id}>
                        <button className="conversation-link" type="button" onClick={() => {
                          setActiveConversation(project.id, conversation.id);
                          navigate("/workspace");
                        }}>
                          <MessageSquareText size={15} /><span><Highlight text={conversation.title} query={searchTerm} /></span>
                        </button>
                        <ConversationMenu title={conversation.title} id={conversation.id} projectId={project.id} onAction={openAction} />
                      </div>
                    ))}
                    {project.conversations.length === 0 && <p className="empty-project">还没有对话</p>}
                  </div>
                </div>
              </section>
            );
          })}
          {normalizedSearch && visibleProjects.length === 0 && (
            <div className="sidebar-empty-search" aria-live="polite"><Search size={18} /><p>抱歉，未查找到相关项目或对话。</p></div>
          )}
        </nav>

        <div className="sidebar-workspace">
          <p className="nav-section-label">工作区</p>
          <NavItem to="/agent" icon={<Bot size={16} />} label="RS-Bot" />
          <NavItem to="/batch" icon={<Layers3 size={16} />} label="批量 VQA" />
          <NavItem to="/reports" icon={<FileClock size={16} />} label="分析报告" />
          <NavItem to="/knowledge" icon={<BookOpen size={16} />} label="知识库" />
          <NavItem to="/audit" icon={<ShieldCheck size={16} />} label="调用审计" />
          <NavItem to="/archive" icon={<Archive size={16} />} label="归档" />
        </div>

        <div className="sidebar-footer">
          <NavItem to="/settings" icon={<Settings size={16} />} label="模型与设置" />
          <DropdownMenu.Root>
            <DropdownMenu.Trigger asChild>
              <button className="profile-row" type="button" aria-label="打开账户菜单">
                <CircleUserRound size={28} strokeWidth={1.5} />
                <div><strong>{user.displayName}</strong><span>{user.demo ? "Demo workspace" : user.username}</span></div>
                <ChevronRight size={15} />
              </button>
            </DropdownMenu.Trigger>
            <DropdownMenu.Portal>
              <DropdownMenu.Content className="context-menu profile-menu" side="right" align="end" sideOffset={10}>
                <div className="profile-menu-heading"><UserRound size={20} /><div><strong>{user.displayName}</strong><small>{user.username}</small></div></div>
                <DropdownMenu.Separator />
                <DropdownMenu.Item onSelect={() => navigate("/settings")}><SlidersHorizontal size={15} />个人与工作区设置</DropdownMenu.Item>
                <DropdownMenu.Item onSelect={() => navigate("/settings")}><Settings size={15} />模型与 Provider</DropdownMenu.Item>
                <DropdownMenu.Separator />
                <DropdownMenu.Item className="danger-item" disabled={logoutMutation.isPending} onSelect={() => logoutMutation.mutate()}><LogOut size={15} />退出登录</DropdownMenu.Item>
              </DropdownMenu.Content>
            </DropdownMenu.Portal>
          </DropdownMenu.Root>
        </div>
      </aside>

      <ActionDialog
        action={action}
        projects={projects}
        value={actionValue}
        pending={actionMutation.isPending}
        onValueChange={setActionValue}
        onClose={() => setAction(null)}
        onSubmit={() => action && actionMutation.mutate(action)}
      />

      {toast && (
        <div className="undo-toast" role="status" aria-live="polite">
          <span>{toast.message}</span>
          <button type="button" onClick={() => {
            toast.undo();
            setToast(null);
          }}>撤销</button>
          <button type="button" aria-label="关闭提示" onClick={() => setToast(null)}><X size={14} /></button>
        </div>
      )}
    </>
  );
}

function ProjectMenu({ project, onCreate, onAction }: { project: Project; onCreate: () => void; onAction: (action: Exclude<Action, null>) => void }) {
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild><button className="icon-button row-menu-trigger" type="button" aria-label={`${project.name}项目菜单`}><MoreHorizontal size={15} /></button></DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content className="context-menu" align="start" sideOffset={4}>
          <DropdownMenu.Item onSelect={onCreate}><Plus size={14} />新建对话</DropdownMenu.Item>
          <DropdownMenu.Item onSelect={() => onAction({ kind: "rename-project", id: project.id, value: project.name })}><Pencil size={14} />重命名</DropdownMenu.Item>
          <DropdownMenu.Separator />
          <DropdownMenu.Item className="danger-item" onSelect={() => onAction({ kind: "archive-project", id: project.id, value: project.name })}><Archive size={14} />归档项目</DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

function ConversationMenu({ id, title, projectId, onAction }: { id: string; title: string; projectId: string; onAction: (action: Exclude<Action, null>) => void }) {
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild><button className="icon-button row-menu-trigger" type="button" aria-label={`${title}对话菜单`}><MoreHorizontal size={14} /></button></DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content className="context-menu" align="start" sideOffset={4}>
          <DropdownMenu.Item onSelect={() => onAction({ kind: "rename-conversation", id, value: title })}><Pencil size={14} />重命名</DropdownMenu.Item>
          <DropdownMenu.Item onSelect={() => onAction({ kind: "move-conversation", id, value: title, projectId })}><Folder size={14} />移动到项目</DropdownMenu.Item>
          <DropdownMenu.Separator />
          <DropdownMenu.Item className="danger-item" onSelect={() => onAction({ kind: "archive-conversation", id, value: title })}><Archive size={14} />归档对话</DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

function ActionDialog({
  action,
  projects,
  value,
  pending,
  onValueChange,
  onClose,
  onSubmit,
}: {
  action: Action;
  projects: Project[];
  value: string;
  pending: boolean;
  onValueChange: (value: string) => void;
  onClose: () => void;
  onSubmit: () => void;
}) {
  const isArchive = action?.kind === "archive-project" || action?.kind === "archive-conversation";
  const isMove = action?.kind === "move-conversation";
  const title = isArchive ? "确认归档" : isMove ? "移动对话" : "重命名";
  return (
    <Dialog.Root open={Boolean(action)} onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="action-dialog" aria-describedby="workspace-action-description">
          <Dialog.Title>{title}</Dialog.Title>
          <Dialog.Description id="workspace-action-description">
            {isArchive ? `“${action?.value}”会移入归档，可在归档页面恢复。` : isMove ? "选择目标项目，历史消息和模型调用会一同保留。" : "输入一个便于识别的新名称。"}
          </Dialog.Description>
          {isMove ? (
            <div className="project-choice-list" role="radiogroup" aria-label="目标项目">
              {projects.map((project) => (
                <button className={value === project.id ? "is-selected" : ""} type="button" role="radio" aria-checked={value === project.id} key={project.id} onClick={() => onValueChange(project.id)}>
                  <Folder size={15} /><span>{project.name}</span>{value === project.id && <Check size={15} />}
                </button>
              ))}
            </div>
          ) : !isArchive && (
            <label className="dialog-field">
              <span>名称</span>
              <input autoFocus name="new-name" autoComplete="off" maxLength={200} value={value} onChange={(event) => onValueChange(event.target.value)} />
            </label>
          )}
          <div className="dialog-actions">
            <Dialog.Close asChild><button className="secondary-button" type="button">取消</button></Dialog.Close>
            <button className={isArchive ? "danger-button" : "primary-button"} type="button" disabled={pending || (!isArchive && !value.trim())} onClick={onSubmit}>
              {pending ? "正在处理…" : isArchive ? "归档" : isMove ? "移动" : "保存"}
            </button>
          </div>
          <Dialog.Close asChild><button className="dialog-close icon-button" type="button" aria-label="关闭"><X size={17} /></button></Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Highlight({ text, query }: { text: string; query: string }) {
  const normalized = query.trim();
  if (!normalized) return text;
  const index = text.toLocaleLowerCase().indexOf(normalized.toLocaleLowerCase());
  if (index < 0) return text;
  return <>{text.slice(0, index)}<mark>{text.slice(index, index + normalized.length)}</mark>{text.slice(index + normalized.length)}</>;
}

function NavItem({ to, icon, label }: { to: string; icon: ReactNode; label: string }) {
  const setSidebarOpen = useWorkspaceStore((state) => state.setSidebarOpen);
  return <NavLink className={({ isActive }) => `nav-link ${isActive ? "is-active" : ""}`} to={to} onClick={() => setSidebarOpen(false)}>{icon}<span>{label}</span></NavLink>;
}
