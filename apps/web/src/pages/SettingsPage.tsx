import { Bot, CheckCircle2, Database, KeyRound, Server, ShieldCheck } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { AppTopbar, modelOptions, providerToModelOption, StatusBadge } from "../App";
import { getSystemStatus, listProviders } from "../api";

export function SettingsPage() {
  const status = useQuery({ queryKey: ["system-status"], queryFn: getSystemStatus, refetchInterval: 10000 });
  const providers = useQuery({ queryKey: ["providers"], queryFn: listProviders, refetchInterval: 10000 });
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
          <div className="section-heading"><div><span>02</span><h3>服务状态</h3></div></div>
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
