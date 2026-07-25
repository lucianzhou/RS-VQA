import { create } from "zustand";

const ACTIVE_NAVIGATION_KEY = "rsvqa:active-navigation";

function readActiveNavigation(): Pick<WorkspaceState, "activeProjectId" | "activeConversationId"> {
  try {
    const value = JSON.parse(sessionStorage.getItem(ACTIVE_NAVIGATION_KEY) ?? "null") as {
      projectId?: unknown;
      conversationId?: unknown;
    } | null;
    return {
      activeProjectId: typeof value?.projectId === "string" ? value.projectId : null,
      activeConversationId: typeof value?.conversationId === "string" ? value.conversationId : null,
    };
  } catch {
    return { activeProjectId: null, activeConversationId: null };
  }
}

function writeActiveNavigation(projectId: string | null, conversationId: string | null) {
  try {
    if (projectId && conversationId) {
      sessionStorage.setItem(ACTIVE_NAVIGATION_KEY, JSON.stringify({ projectId, conversationId }));
    } else {
      sessionStorage.removeItem(ACTIVE_NAVIGATION_KEY);
    }
  } catch {
    // Storage can be unavailable in privacy-restricted browsers; in-memory state still works.
  }
}

interface WorkspaceState {
  activeProjectId: string | null;
  activeConversationId: string | null;
  sidebarOpen: boolean;
  sidebarCollapsed: boolean;
  selectedModelId: string;
  signedOut: boolean;
  setSidebarOpen: (open: boolean) => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setActiveConversation: (projectId: string, conversationId: string) => void;
  clearActiveConversation: () => void;
  setSelectedModelId: (id: string) => void;
  setSignedOut: (signedOut: boolean) => void;
}

export const useWorkspaceStore = create<WorkspaceState>((set) => ({
  ...readActiveNavigation(),
  sidebarOpen: false,
  sidebarCollapsed: false,
  selectedModelId: "research-rsvqa",
  signedOut: false,
  setSidebarOpen: (sidebarOpen) => set({ sidebarOpen }),
  setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
  setActiveConversation: (activeProjectId, activeConversationId) =>
    (writeActiveNavigation(activeProjectId, activeConversationId), set({ activeProjectId, activeConversationId, sidebarOpen: false })),
  clearActiveConversation: () =>
    (writeActiveNavigation(null, null), set({ activeProjectId: null, activeConversationId: null })),
  setSelectedModelId: (selectedModelId) => set({ selectedModelId }),
  setSignedOut: (signedOut) => set({ signedOut }),
}));
