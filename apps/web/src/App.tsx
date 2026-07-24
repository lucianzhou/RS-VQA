import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, ShieldCheck } from "lucide-react";
import { Navigate, Route, Routes } from "react-router-dom";
import { demoLogin } from "./api";
import { AppSidebar } from "./components/AppSidebar";
import {
  AppTopbar,
  ModelSelector,
  StatusBadge,
  modelOptions,
  providerToModelOption,
} from "./components/AppChrome";
import { ArchivePage } from "./pages/ArchivePage";
import { AuditPage } from "./pages/AuditPage";
import { BatchPage } from "./pages/BatchPage";
import { KnowledgePage } from "./pages/KnowledgePage";
import { SettingsPage } from "./pages/SettingsPage";
import { WorkspacePage } from "./pages/WorkspacePage";
import { useWorkspaceStore } from "./store";

export { AppTopbar, ModelSelector, StatusBadge, modelOptions, providerToModelOption };

export function App() {
  const queryClient = useQueryClient();
  const sidebarCollapsed = useWorkspaceStore((state) => state.sidebarCollapsed);
  const signedOut = useWorkspaceStore((state) => state.signedOut);
  const setSignedOut = useWorkspaceStore((state) => state.setSignedOut);
  const session = useQuery({
    queryKey: ["session"],
    queryFn: demoLogin,
    retry: 1,
    staleTime: Infinity,
    enabled: !signedOut,
  });

  if (signedOut) {
    return <SignedOut onContinue={() => {
      queryClient.removeQueries({ queryKey: ["session"] });
      setSignedOut(false);
    }} />;
  }
  if (session.isPending) {
    return <BootstrapState title="正在准备工作区" detail="建立本地演示会话…" />;
  }
  if (session.isError) {
    return <BootstrapState title="无法连接业务服务" detail={session.error.message} error />;
  }
  return (
    <div className={`app-shell ${sidebarCollapsed ? "sidebar-collapsed" : ""}`}>
      <AppSidebar user={session.data} />
      <div className="app-content">
        <a className="skip-link" href="#main-content">跳到主要内容</a>
        <div id="main-content" className="route-content">
          <Routes>
            <Route path="/" element={<Navigate to="/workspace" replace />} />
            <Route path="/workspace" element={<WorkspacePage />} />
            <Route path="/batch" element={<BatchPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/knowledge" element={<KnowledgePage />} />
            <Route path="/audit" element={<AuditPage />} />
            <Route path="/archive" element={<ArchivePage />} />
            <Route path="*" element={<Navigate to="/workspace" replace />} />
          </Routes>
        </div>
      </div>
    </div>
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

function SignedOut({ onContinue }: { onContinue: () => void }) {
  return (
    <main className="signed-out">
      <div className="signed-out-mark"><ShieldCheck size={24} /></div>
      <p className="eyebrow">RS-VQA MINERAL FOREST</p>
      <h1>已安全退出本地工作区</h1>
      <p>当前是论文演示环境。再次进入会建立新的受控 Demo 会话，不会使用浏览器账户或外部 API 凭据。</p>
      <button className="primary-button" type="button" onClick={onContinue}>进入本地演示<ArrowRight size={16} /></button>
    </main>
  );
}
