import { create } from "zustand";

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
  activeProjectId: null,
  activeConversationId: null,
  sidebarOpen: false,
  sidebarCollapsed: false,
  selectedModelId: "research-rsvqa",
  signedOut: false,
  setSidebarOpen: (sidebarOpen) => set({ sidebarOpen }),
  setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
  setActiveConversation: (activeProjectId, activeConversationId) =>
    set({ activeProjectId, activeConversationId, sidebarOpen: false }),
  clearActiveConversation: () => set({ activeProjectId: null, activeConversationId: null }),
  setSelectedModelId: (selectedModelId) => set({ selectedModelId }),
  setSignedOut: (signedOut) => set({ signedOut }),
}));
