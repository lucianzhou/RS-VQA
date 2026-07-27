import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AnimatePresence, motion } from "motion/react";
import {
  AlertTriangle,
  BarChart3,
  Check,
  Download,
  FileCheck2,
  FileText,
  History,
  LoaderCircle,
  Plus,
  RefreshCw,
  ShieldCheck,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import {
  confirmReport,
  createReport,
  getReport,
  listBatchJobs,
  listProjects,
  listReports,
  regenerateReport,
} from "../api";
import { AppTopbar, StatusBadge } from "../components/AppChrome";
import { useWorkspaceStore } from "../store";
import type { AnalysisCase, AnalysisStatistics } from "../types";

export function ReportsPage() {
  const queryClient = useQueryClient();
  const activeProjectId = useWorkspaceStore((state) => state.activeProjectId);
  const [activeReportId, setActiveReportId] = useState<string>();
  const [scopeType, setScopeType] = useState<"project" | "batch">("project");
  const [scopeId, setScopeId] = useState(activeProjectId ?? "");
  const reports = useQuery({ queryKey: ["reports"], queryFn: listReports });
  const projects = useQuery({ queryKey: ["projects"], queryFn: listProjects });
  const batches = useQuery({ queryKey: ["batch-jobs"], queryFn: listBatchJobs });
  const detail = useQuery({
    queryKey: ["report", activeReportId],
    queryFn: () => getReport(activeReportId!),
    enabled: Boolean(activeReportId),
  });

  useEffect(() => {
    if (!activeReportId && reports.data?.[0]) setActiveReportId(reports.data[0].id);
  }, [activeReportId, reports.data]);
  useEffect(() => {
    if (scopeId) return;
    if (scopeType === "project" && projects.data?.[0]) setScopeId(projects.data[0].id);
    if (scopeType === "batch" && batches.data?.[0]) setScopeId(batches.data[0].id);
  }, [batches.data, projects.data, scopeId, scopeType]);

  const create = useMutation({
    mutationFn: () => createReport(scopeType === "project" ? { projectId: scopeId } : { batchJobId: scopeId }),
    onSuccess: async (created) => {
      setActiveReportId(created.report.id);
      queryClient.setQueryData(["report", created.report.id], created);
      await queryClient.invalidateQueries({ queryKey: ["reports"] });
    },
  });
  const regenerate = useMutation({
    mutationFn: (id: string) => regenerateReport(id),
    onSuccess: async (updated) => {
      queryClient.setQueryData(["report", updated.report.id], updated);
      await queryClient.invalidateQueries({ queryKey: ["reports"] });
    },
  });
  const confirm = useMutation({
    mutationFn: (id: string) => confirmReport(id),
    onSuccess: async (updated) => {
      queryClient.setQueryData(["report", updated.report.id], updated);
      await queryClient.invalidateQueries({ queryKey: ["reports"] });
    },
  });
  const facts = useMemo(() => {
    if (!detail.data?.current.factsJson) return undefined;
    try {
      return JSON.parse(detail.data.current.factsJson) as AnalysisStatistics;
    } catch {
      return undefined;
    }
  }, [detail.data?.current.factsJson]);
  const scopeOptions = scopeType === "project" ? projects.data ?? [] : batches.data ?? [];

  return (
    <main className="page">
      <AppTopbar title="分析报告" subtitle="确定性统计 · Agent 解释 · 人工确认" />
      <div className="page-scroll">
        <div className="report-layout">
        <header className="page-intro">
          <div>
            <StatusBadge tone="success">事实与解释分层</StatusBadge>
            <h2>把项目与批任务整理为可追溯报告</h2>
            <p>后端负责计算数量、分布与复核清单；Agent 只能在这些事实之上生成带来源的解释。</p>
          </div>
        </header>

        <section className="report-create-bar" aria-label="创建报告">
          <div className="segmented-control" aria-label="报告范围">
            <button className={scopeType === "project" ? "is-active" : ""} type="button" onClick={() => {
              setScopeType("project");
              setScopeId(projects.data?.[0]?.id ?? "");
            }}>项目</button>
            <button className={scopeType === "batch" ? "is-active" : ""} type="button" onClick={() => {
              setScopeType("batch");
              setScopeId(batches.data?.[0]?.id ?? "");
            }}>批量任务</button>
          </div>
          <label>
            <span className="sr-only">选择报告范围</span>
            <select value={scopeId} onChange={(event) => setScopeId(event.target.value)}>
              {scopeOptions.map((item) => (
                <option key={item.id} value={item.id}>
                  {"name" in item ? item.name : `批量任务 ${new Date(item.createdAt).toLocaleString("zh-CN")}`}
                </option>
              ))}
            </select>
          </label>
          <button className="primary-button" type="button" disabled={!scopeId || create.isPending} onClick={() => create.mutate()}>
            {create.isPending ? <LoaderCircle className="spin" size={15} /> : <Plus size={15} />}
            {create.isPending ? "正在计算…" : "生成确定性草稿"}
          </button>
        </section>

        {(create.isError || detail.isError) && (
          <div className="inline-error" role="alert"><AlertTriangle size={15} />{create.error?.message ?? detail.error?.message}</div>
        )}

        <div className="report-workspace">
          <aside className="report-index" aria-label="报告列表">
            <header><span><FileText size={15} />报告</span><small>{reports.data?.length ?? 0}</small></header>
            {reports.isPending && <p>正在读取…</p>}
            {reports.data?.map((report) => (
              <button className={report.id === activeReportId ? "is-active" : ""} type="button" key={report.id} onClick={() => setActiveReportId(report.id)}>
                <span>{report.status === "CONFIRMED" ? <FileCheck2 size={15} /> : <FileText size={15} />}</span>
                <div><strong>{report.title}</strong><small>v{report.currentVersion} · {report.status === "CONFIRMED" ? "已确认" : "草稿"}</small></div>
              </button>
            ))}
            {reports.data?.length === 0 && <p>尚未生成报告。</p>}
          </aside>

          <section className="report-document">
            {!activeReportId && (
              <div className="report-empty"><BarChart3 size={24} /><strong>选择范围并生成第一份报告</strong><p>报告会保留生成时的事实包、模型来源、版本和请求编号。</p></div>
            )}
            {detail.isPending && activeReportId && <div className="report-empty"><LoaderCircle className="spin" size={22} /><p>正在恢复报告版本…</p></div>}
            <AnimatePresence mode="wait">
              {detail.data && facts && (
                <motion.div key={`${detail.data.report.id}:${detail.data.report.currentVersion}`} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }}>
                  <header className="report-document-heading">
                    <div>
                      <span className={`report-state ${detail.data.report.status.toLowerCase()}`}>{detail.data.report.status === "CONFIRMED" ? <Check size={13} /> : <History size={13} />}{detail.data.report.status === "CONFIRMED" ? "人工已确认" : "待人工确认"}</span>
                      <h3>{detail.data.report.title}</h3>
                      <p>v{detail.data.report.currentVersion} · {detail.data.current.generatedBy} · {detail.data.current.predictionOrigin}</p>
                    </div>
                    <div>
                      <button className="quiet-button" type="button" disabled={regenerate.isPending} onClick={() => regenerate.mutate(detail.data.report.id)}><RefreshCw size={14} />重算新版本</button>
                      {detail.data.report.status !== "CONFIRMED" && <button className="primary-button" type="button" disabled={confirm.isPending} onClick={() => confirm.mutate(detail.data.report.id)}><ShieldCheck size={14} />人工确认</button>}
                    </div>
                  </header>

                  <div className="report-metrics">
                    <Metric label="图像" value={facts.imageCount} />
                    <Metric label="问题/调用" value={facts.questionCount} />
                    <Metric label="已回答" value={facts.answeredCount} />
                    <Metric label="明确复核" value={facts.reviewRecommendedCount} tone={facts.reviewRecommendedCount ? "warning" : undefined} />
                    <Metric label="超范围" value={facts.unsupportedCount} tone={facts.unsupportedCount ? "warning" : undefined} />
                    <Metric label="失败" value={facts.failedCount} tone={facts.failedCount ? "danger" : undefined} />
                  </div>

                  <div className="report-distributions">
                    <Distribution title="问题类型" values={facts.questionTypeDistribution} />
                    <Distribution title="置信度区间" values={facts.confidenceDistribution} />
                    <Distribution title="输出来源" values={facts.originDistribution} />
                  </div>

                  <section className="review-section">
                    <div className="section-heading"><div><span>REVIEW</span><h3>需要人工复核</h3></div><small>{facts.reviewCases.length} 项</small></div>
                    {facts.reviewCases.length === 0 ? <p className="empty-copy"><Check size={14} />当前没有超范围、调用失败或答案形式异常案例。</p> : (
                      <div className="review-table">
                        {facts.reviewCases.map((item) => (
                          <article key={item.scopeItemId}>
                            <div><strong>{item.scopeLabel}</strong><p>{item.question}</p></div>
                            <span>{item.answer ?? item.status} · {reviewReasonLabel(item.reviewReason)}</span>
                            <em>{item.confidence == null ? "N/A" : `${(item.confidence * 100).toFixed(1)}%`}</em>
                          </article>
                        ))}
                      </div>
                    )}
                  </section>

                  <footer className="report-provenance">
                    <div><strong>模型发布</strong><span>{facts.modelReleaseIds.join(", ") || "无已记录发布"}</span></div>
                    <div><strong>请求编号</strong><span>{detail.data.report.requestId}</span></div>
                    <p><ShieldCheck size={14} />{facts.calculationBoundary}</p>
                    <div className="report-export-actions">
                      <a className="quiet-button" href={`/api/v1/reports/${detail.data.report.id}/export?format=md`}><Download size={14} />Markdown</a>
                      <a className="quiet-button" href={`/api/v1/reports/${detail.data.report.id}/export?format=json`}><Download size={14} />JSON</a>
                    </div>
                  </footer>

                  <details className="report-source-preview"><summary>查看当前版本 Markdown</summary><pre>{detail.data.current.markdownContent}</pre></details>
                </motion.div>
              )}
            </AnimatePresence>
          </section>
        </div>
        </div>
      </div>
    </main>
  );
}

function Metric({ label, value, tone }: { label: string; value: number; tone?: string }) {
  return <div className={tone ? `is-${tone}` : ""}><strong>{value}</strong><span>{label}</span></div>;
}

function Distribution({ title, values }: { title: string; values: Record<string, number> }) {
  const entries = Object.entries(values);
  const max = Math.max(1, ...entries.map(([, value]) => value));
  return (
    <section>
      <h4>{title}</h4>
      {entries.length === 0 ? <p>暂无数据</p> : entries.map(([label, value]) => (
        <div className="distribution-row" key={label}>
          <span title={label}>{originLabel(label)}</span>
          <i><b style={{ width: `${value * 100 / max}%` }} /></i>
          <em>{value}</em>
        </div>
      ))}
    </section>
  );
}

function originLabel(value: string) {
  if (value === "mock_demo") return "Mock 演示";
  if (value === "research_vilt_predicted_soft") return "研究模型";
  if (value === "external_vlm_assist") return "外部 VLM";
  if (value === "not_applicable") return "不适用";
  return value;
}

function reviewReasonLabel(value: AnalysisCase["reviewReason"]) {
  if (value === "unsupported") return "超出范围";
  if (value === "failed") return "调用失败";
  if (value === "answer_shape_mismatch") return "答案形式异常";
  return "待复核";
}
