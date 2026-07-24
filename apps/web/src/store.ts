import { create } from "zustand";

interface WorkspaceState {
  activeProjectId: string | null;
  activeConversationId: string | null;
  sidebarOpen: boolean;
  sidebarCollapsed: boolean;
  selectedModelId: string;
  setSidebarOpen: (open: boolean) => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setActiveConversation: (projectId: string, conversationId: string) => void;
  setSelectedModelId: (id: string) => void;
}

export const useWorkspaceStore = create<WorkspaceState>((set) => ({
  activeProjectId: null,
  activeConversationId: null,
  sidebarOpen: false,
  sidebarCollapsed: false,
  selectedModelId: "research-rsvqa",
  setSidebarOpen: (sidebarOpen) => set({ sidebarOpen }),
  setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
  setActiveConversation: (activeProjectId, activeConversationId) =>
    set({ activeProjectId, activeConversationId, sidebarOpen: false }),
  setSelectedModelId: (selectedModelId) => set({ selectedModelId }),
}));
