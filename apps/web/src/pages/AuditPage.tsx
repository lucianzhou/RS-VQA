import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, Clock3, ShieldCheck, XCircle } from "lucide-react";
import { AppTopbar, StatusBadge } from "../components/AppChrome";
import { listMyAuditEvents } from "../api";

export function AuditPage() {
  const events = useQuery({ queryKey: ["audit-events"], queryFn: listMyAuditEvents, refetchInterval: 5000 });
  return (
    <main className="page">
      <AppTopbar title="调用审计" subtitle="最近 100 条当前用户操作 · 不记录凭据与请求正文" />
      <div className="page-scroll settings-layout">
        <header className="page-intro"><div><StatusBadge><ShieldCheck size={12} />可追踪</StatusBadge><h2>从回答回到请求，从工具回到 Trace</h2><p>这里只保存操作类型、结果、时间和 Trace ID；密码、Token、Cookie、图像内容及问题正文不会写入审计摘要。</p></div></header>
        <section className="plain-section">
          <div className="section-heading"><div><span>01</span><h3>操作轨迹</h3></div></div>
          <div className="audit-list">
            {(events.data ?? []).map((event) => <article key={event.id}>
              <span className={event.outcome === "SUCCESS" ? "success" : "failure"}>{event.outcome === "SUCCESS" ? <CheckCircle2 size={15} /> : <XCircle size={15} />}</span>
              <div><strong>{event.eventType}</strong><small>{event.summary} · Trace {event.traceId}</small></div>
              <time><Clock3 size={12} />{new Date(event.createdAt).toLocaleString("zh-CN")}</time>
            </article>)}
            {events.isPending && <p className="empty-copy">正在读取审计记录…</p>}
            {events.data?.length === 0 && <p className="empty-copy">尚无已记录的变更操作。</p>}
          </div>
        </section>
      </div>
    </main>
  );
}
