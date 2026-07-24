import { Accessibility, Bot, CheckCircle2, Database, Globe2, KeyRound, Server, ShieldCheck } from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { AppTopbar, modelOptions, providerToModelOption, StatusBadge } from "../components/AppChrome";
import { getSystemStatus, getUserSettings, listProviders, updateUserSettings } from "../api";

export function SettingsPage() {
  const queryClient = useQueryClient();
  const status = useQuery({ queryKey: ["system-status"], queryFn: getSystemStatus, refetchInterval: 10000 });
  const providers = useQuery({ queryKey: ["providers"], queryFn: listProviders, refetchInterval: 10000 });
  const userSettings = useQuery({ queryKey: ["user-settings"], queryFn: getUserSettings });
  const updateSettings = useMutation({
    mutationFn: updateUserSettings,
    onSuccess: (updated) => queryClient.setQueryData(["user-settings"], updated),
  });
  const availableModels = Array.isArray(providers.data) ? providers.data.map(providerToModelOption) : modelOptions;
  const service = (name: string, fallback: string) => {
    const value = status.data?.services[name];
    if (!value) return fallback;
    const detail = Object.entries(value).filter(([key]) => key !== "status").map(([, item]) => String(item)).join(" · ");
    return `${value.status}${detail ? ` · ${detail}` : ""}`;
  };
  return (
    <main className="page">
      <AppTopbar title="模型与设置" subtitle="能力、来源和运行状态" />
      <div className="page-scroll settings-layout">
        <header className="page-intro"><div><StatusBadge tone="success">来源隔离已启用</StatusBadge><h2>每一次回答都保留自己的模型身份</h2><p>研究模型、外部视觉模型和 Mock 不共享模糊标签；历史消息不会被当前配置覆盖。</p></div></header>
        <section className="plain-section">
          <div className="section-heading"><div><span>01</span><h3>可用模型</h3></div></div>
          <div className="model-list">
            {availableModels.map((model) => (
              <article key={model.id}>
                <span className="model-icon">{model.kind === "EXTERNAL_VLM" ? <Bot size={20} /> : <ShieldCheck size={20} />}</span>
                <div>
                  <div className="model-title"><h4>{model.name}</h4><span className={model.configured ? "configured" : "unconfigured"}>{model.configured ? <><CheckCircle2 size={13} />{model.kind === "MOCK" ? "Mock 可用" : "开发可用"}</> : "未配置"}</span></div>
                  <p>{model.description}</p>
                  <small>{model.releaseId ?? "需要单独配置 Provider 凭据；网页登录状态不会被用作 API 授权。"}</small>
                </div>
              </article>
            ))}
          </div>
        </section>
        <section className="plain-section">
          <div className="section-heading"><div><span>02</span><h3>个人与隐私偏好</h3></div></div>
          {userSettings.isPending && <p className="empty-copy">正在读取偏好…</p>}
          {userSettings.isError && <p className="inline-error">{userSettings.error.message}</p>}
          {userSettings.data && (
            <div className="preference-list">
              <Preference
                icon={<Globe2 size={18} />}
                title="界面语言"
                description="当前产品文案以中文为主，英文界面结构已预留。"
                control={(
                  <select aria-label="界面语言" value={userSettings.data.locale} onChange={(event) => updateSettings.mutate({ locale: event.target.value as "zh-CN" | "en-US" })}>
                    <option value="zh-CN">简体中文</option>
                    <option value="en-US">English</option>
                  </select>
                )}
              />
              <Preference
                icon={<Accessibility size={18} />}
                title="减少动态效果"
                description="降低明显的位置移动，保留必要的淡入和状态反馈。"
                control={<Switch checked={userSettings.data.reducedMotion} label="减少动态效果" onChange={(checked) => updateSettings.mutate({ reducedMotion: checked })} />}
              />
              <Preference
                icon={<ShieldCheck size={18} />}
                title="允许向外部视觉 Provider 发送图像"
                description={userSettings.data.externalImageBoundary}
                warning
                control={<Switch checked={userSettings.data.externalImageOptIn} label="外部图像发送许可" onChange={(checked) => updateSettings.mutate({ externalImageOptIn: checked })} />}
              />
            </div>
          )}
        </section>
        <section className="plain-section">
          <div className="section-heading"><div><span>03</span><h3>服务状态</h3></div></div>
          <div className="service-grid">
            <Service icon={<Server size={18} />} name="模型服务" value={service("model", "正在检查")} />
            <Service icon={<Database size={18} />} name="PostgreSQL / Redis" value={`${service("database", "正在检查")} · ${service("redis", "正在检查")}`} />
            <Service icon={<Bot size={18} />} name="Agent / MCP" value={`${service("agent", "正在检查")} · ${service("mcp", "正在检查")}`} />
            <Service icon={<Database size={18} />} name="BGE / Milvus" value={service("knowledge", "正在检查")} />
            <Service icon={<KeyRound size={18} />} name="外部 Provider" value="未配置" />
          </div>
        </section>
        <aside className="boundary-note"><ShieldCheck size={19} /><div><strong>研究模型能力边界</strong><p>当前候选是 RSVQA-HR 特定答案词表的闭集分类器，不是开放式视觉助手、目标检测或零样本识别模型。Mock 回答不能用于论文结论。</p></div></aside>
      </div>
    </main>
  );
}

function Service({ icon, name, value }: { icon: ReactNode; name: string; value: string }) {
  return <div className="service-item"><span>{icon}</span><div><strong>{name}</strong><small>{value}</small></div></div>;
}

function Preference({ icon, title, description, control, warning = false }: { icon: ReactNode; title: string; description: string; control: ReactNode; warning?: boolean }) {
  return <div className={`preference-item ${warning ? "is-warning" : ""}`}><span>{icon}</span><div><strong>{title}</strong><small>{description}</small></div>{control}</div>;
}

function Switch({ checked, label, onChange }: { checked: boolean; label: string; onChange: (checked: boolean) => void }) {
  return <button className={`switch-control ${checked ? "is-checked" : ""}`} type="button" role="switch" aria-checked={checked} aria-label={label} onClick={() => onChange(!checked)}><span /></button>;
}
