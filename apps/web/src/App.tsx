import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, ShieldCheck } from "lucide-react";
import { AnimatePresence, MotionConfig, motion } from "motion/react";
import { lazy, Suspense } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { demoLogin, getUserSettings } from "./api";
import { AppSidebar } from "./components/AppSidebar";
import {
  AppTopbar,
  ModelSelector,
  StatusBadge,
  modelOptions,
  providerToModelOption,
} from "./components/AppChrome";
import { useWorkspaceStore } from "./store";

export { AppTopbar, ModelSelector, StatusBadge, modelOptions, providerToModelOption };

const WorkspacePage = lazy(() => import("./pages/WorkspacePage").then((module) => ({ default: module.WorkspacePage })));
const BatchPage = lazy(() => import("./pages/BatchPage").then((module) => ({ default: module.BatchPage })));
const SettingsPage = lazy(() => import("./pages/SettingsPage").then((module) => ({ default: module.SettingsPage })));
const KnowledgePage = lazy(() => import("./pages/KnowledgePage").then((module) => ({ default: module.KnowledgePage })));
const AuditPage = lazy(() => import("./pages/AuditPage").then((module) => ({ default: module.AuditPage })));
const ArchivePage = lazy(() => import("./pages/ArchivePage").then((module) => ({ default: module.ArchivePage })));
const ReportsPage = lazy(() => import("./pages/ReportsPage").then((module) => ({ default: module.ReportsPage })));

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
  const userSettings = useQuery({
    queryKey: ["user-settings"],
    queryFn: getUserSettings,
    enabled: session.isSuccess && !signedOut,
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
    <MotionConfig reducedMotion={userSettings.data?.reducedMotion ? "always" : "user"}>
      <div className={`app-shell ${sidebarCollapsed ? "sidebar-collapsed" : ""}`}>
        <AppSidebar user={session.data} />
        <div className="app-content">
          <a className="skip-link" href="#main-content">跳到主要内容</a>
          <div id="main-content" className="route-content">
            <AnimatedRoutes />
          </div>
        </div>
      </div>
    </MotionConfig>
  );
}

function AnimatedRoutes() {
  const location = useLocation();
  return (
    <AnimatePresence mode="wait" initial={false}>
      <motion.div
        className="route-frame"
        key={location.pathname}
        initial={{ opacity: 0, y: 5 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: -3 }}
        transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}
      >
        <Suspense fallback={<RouteLoading />}>
          <Routes location={location}>
            <Route path="/" element={<Navigate to="/workspace" replace />} />
            <Route path="/workspace" element={<WorkspacePage />} />
            <Route path="/batch" element={<BatchPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/knowledge" element={<KnowledgePage />} />
            <Route path="/audit" element={<AuditPage />} />
            <Route path="/archive" element={<ArchivePage />} />
            <Route path="/reports" element={<ReportsPage />} />
            <Route path="*" element={<Navigate to="/workspace" replace />} />
          </Routes>
        </Suspense>
      </motion.div>
    </AnimatePresence>
  );
}

function RouteLoading() {
  return <div className="route-loading" role="status"><span className="route-loading-mark" />正在切换工作区…</div>;
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
