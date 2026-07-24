import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Download, FileImage, FolderUp, Layers3, LoaderCircle, Plus, RotateCcw, StopCircle, Trash2, UploadCloud } from "lucide-react";
import { useRef, useState } from "react";
import { AppTopbar, ModelSelector, StatusBadge } from "../App";
import { cancelBatchJob, createBatchJob, getBatchJob, listBatchJobs, retryBatchFailures } from "../api";
import { useWorkspaceStore } from "../store";

export function BatchPage() {
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [questions, setQuestions] = useState(["图中有没有道路？", "图中有多少建筑物？"]);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [activeJobId, setActiveJobId] = useState<string>();
  const activeProjectId = useWorkspaceStore((state) => state.activeProjectId);
  const jobsQuery = useQuery({ queryKey: ["batch-jobs"], queryFn: listBatchJobs, refetchInterval: 3000 });
  const jobQuery = useQuery({
    queryKey: ["batch-job", activeJobId],
    queryFn: () => getBatchJob(activeJobId!),
    enabled: Boolean(activeJobId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === "QUEUED" || status === "RUNNING" ? 800 : false;
    },
  });
  const createMutation = useMutation({
    mutationFn: () => createBatchJob(files, questions.map((question) => question.trim()), activeProjectId, setUploadProgress),
    onSuccess: async (job) => {
      setActiveJobId(job.id);
      setFiles([]);
      await queryClient.invalidateQueries({ queryKey: ["batch-jobs"] });
    },
  });
  const cancelMutation = useMutation({
    mutationFn: (jobId: string) => cancelBatchJob(jobId),
    onSuccess: (job) => queryClient.setQueryData(["batch-job", job.id], job),
  });
  const retryMutation = useMutation({
    mutationFn: (jobId: string) => retryBatchFailures(jobId),
    onSuccess: (job) => queryClient.setQueryData(["batch-job", job.id], job),
  });
  const activeJob = jobQuery.data;

  return (
    <main className="page">
      <AppTopbar title="批量 VQA" subtitle="多图像 · 多问题 · 可恢复任务" actions={<ModelSelector />} />
      <div className="page-scroll batch-layout">
        <header className="page-intro">
          <div><StatusBadge>工作流配置</StatusBadge><h2>建立一组可复核的批量问答任务</h2><p>先选择图像和问题。正式创建任务后，每一个组合都会保留模型版本与独立状态。</p></div>
          <div className="batch-summary"><span><strong>{files.length}</strong>图像</span><span><strong>{questions.length}</strong>问题</span><span><strong>{files.length * questions.length}</strong>组合</span></div>
        </header>
        <section className="plain-section">
          <div className="section-heading"><div><span>01</span><h3>选择图像</h3></div><button className="quiet-button" type="button" onClick={() => input.current?.click()}><FolderUp size={15} />添加图像</button></div>
          <button className="batch-dropzone" type="button" onClick={() => input.current?.click()}><UploadCloud size={22} /><strong>选择多张遥感图像</strong><span>PNG、JPG、WEBP · 单张最大 10 MiB</span></button>
          <input ref={input} className="sr-only" type="file" multiple accept="image/png,image/jpeg,image/webp" onChange={(event) => {
            const selected = Array.from(event.target.files ?? []).filter((file) => file.size <= 10 * 1024 * 1024);
            setFiles(selected.slice(0, 32));
            event.target.value = "";
          }} />
          {files.length > 0 && <div className="file-list">{files.slice(0, 8).map((file) => <div key={`${file.name}-${file.size}`}><FileImage size={16} /><span>{file.name}</span><small>{(file.size / 1024).toFixed(0)} KiB</small></div>)}</div>}
        </section>
        <section className="plain-section">
          <div className="section-heading"><div><span>02</span><h3>设置问题</h3></div><button className="quiet-button" type="button" onClick={() => setQuestions([...questions, ""])}><Plus size={15} />添加问题</button></div>
          <div className="question-list">{questions.map((question, index) => <div key={index}><span>{String(index + 1).padStart(2, "0")}</span><input aria-label={`问题 ${index + 1}`} value={question} maxLength={300} onChange={(event) => setQuestions(questions.map((item, itemIndex) => itemIndex === index ? event.target.value : item))} /><button className="icon-button destructive" type="button" aria-label={`删除问题 ${index + 1}`} onClick={() => setQuestions(questions.filter((_, itemIndex) => itemIndex !== index))}><Trash2 size={15} /></button></div>)}</div>
        </section>
        {createMutation.isError && <div className="inline-error" role="alert"><AlertTriangle size={15} />{createMutation.error.message}</div>}
        {createMutation.isPending && <div className="upload-progress" aria-label={`上传进度 ${uploadProgress}%`}><span style={{ width: `${uploadProgress}%` }} /><small>正在上传并建立任务 · {uploadProgress}%</small></div>}
        <footer className="batch-footer"><p><Layers3 size={15} />任务会逐项保存；单项失败不会丢失其他进度。</p><button className="primary-button" type="button" onClick={() => createMutation.mutate()} disabled={createMutation.isPending || files.length === 0 || questions.some((question) => !question.trim())}>{createMutation.isPending ? "正在创建…" : "创建批量任务"}</button></footer>

        {(activeJob || (jobsQuery.data?.length ?? 0) > 0) && (
          <section className="plain-section batch-history">
            <div className="section-heading"><div><span>03</span><h3>任务进度与结果</h3></div></div>
            <div className="job-tabs">
              {(jobsQuery.data ?? []).map((job) => <button className={job.id === activeJobId ? "is-active" : ""} type="button" key={job.id} onClick={() => setActiveJobId(job.id)}>{new Date(job.createdAt).toLocaleString("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" })}<small>{job.status}</small></button>)}
            </div>
            {activeJob ? (
              <div className="job-detail">
                <div className="job-progress-heading">
                  <div><strong>{statusLabel(activeJob.status)}</strong><span>{activeJob.completedItems} / {activeJob.totalItems} 项 · 失败 {activeJob.failedItems}</span></div>
                  <div>
                    {!["QUEUED", "RUNNING"].includes(activeJob.status) && <a className="quiet-button" href={`/api/v1/batch-jobs/${activeJob.id}/export.csv`}><Download size={14} />导出 CSV</a>}
                    {(activeJob.status === "QUEUED" || activeJob.status === "RUNNING") && <button className="quiet-button" type="button" disabled={cancelMutation.isPending} onClick={() => cancelMutation.mutate(activeJob.id)}><StopCircle size={14} />取消</button>}
                    {activeJob.failedItems > 0 && !["QUEUED", "RUNNING"].includes(activeJob.status) && <button className="quiet-button" type="button" disabled={retryMutation.isPending} onClick={() => retryMutation.mutate(activeJob.id)}><RotateCcw size={14} />重试失败项</button>}
                  </div>
                </div>
                <div className="job-progress"><span style={{ width: `${activeJob.progressPercent}%` }} /></div>
                <div className="batch-results">
                  {activeJob.items.map((item) => (
                    <article key={item.id}>
                      <span className={`item-state ${item.status.toLowerCase()}`}>{item.status === "RUNNING" ? <LoaderCircle className="spin" size={14} /> : item.status === "COMPLETED" ? <CheckCircle2 size={14} /> : item.status === "FAILED" ? <AlertTriangle size={14} /> : <Layers3 size={14} />}</span>
                      <div><strong>{item.imageName}</strong><p>{item.question}</p><small>{item.answer ?? item.errorMessage ?? item.status}</small></div>
                      {item.confidence != null && <em>{(item.confidence * 100).toFixed(1)}%</em>}
                    </article>
                  ))}
                </div>
              </div>
            ) : <button className="quiet-button" type="button" onClick={() => setActiveJobId(jobsQuery.data?.[0]?.id)}>查看最近任务</button>}
          </section>
        )}
      </div>
    </main>
  );
}

function statusLabel(status: string) {
  return ({
    QUEUED: "任务已排队",
    RUNNING: "正在逐项分析",
    COMPLETED: "任务已完成",
    COMPLETED_WITH_ERRORS: "任务完成，部分项目失败",
    CANCELLED: "任务已取消",
  } as Record<string, string>)[status] ?? status;
}
