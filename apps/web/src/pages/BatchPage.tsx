import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Archive, CheckCircle2, ChevronLeft, ChevronRight, Download, Layers3, LoaderCircle, Maximize2, Plus, RotateCcw, StopCircle, Trash2, UploadCloud, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { AppTopbar, ModelSelector, StatusBadge } from "../components/AppChrome";
import { ImageLightbox } from "../components/ImageLightbox";
import { archiveBatchJob, cancelBatchJob, createBatchJob, getBatchJob, listBatchJobs, retryBatchFailures } from "../api";
import { useWorkspaceStore } from "../store";

const IMAGES_PER_PAGE = 20;
const MAX_IMAGES = 200;
const MAX_COMBINATIONS = 1000;
const MAX_TOTAL_IMAGE_BYTES = 120 * 1024 * 1024;

export function BatchPage() {
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [questions, setQuestions] = useState(["图中有没有道路？", "图中有多少建筑物？"]);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [activeJobId, setActiveJobId] = useState<string>();
  const [imagePage, setImagePage] = useState(1);
  const [previewIndex, setPreviewIndex] = useState<number>();
  const [selectionNotice, setSelectionNotice] = useState("");
  const activeProjectId = useWorkspaceStore((state) => state.activeProjectId);
  const previews = useMemo(
    () => files.map((file) => ({ file, url: URL.createObjectURL(file) })),
    [files],
  );
  useEffect(() => () => previews.forEach((preview) => URL.revokeObjectURL(preview.url)), [previews]);
  const pageCount = Math.max(1, Math.ceil(files.length / IMAGES_PER_PAGE));
  const pageStart = (imagePage - 1) * IMAGES_PER_PAGE;
  const visiblePreviews = previews.slice(pageStart, pageStart + IMAGES_PER_PAGE);
  const selectedPreview = previewIndex == null ? undefined : previews[previewIndex];

  useEffect(() => {
    if (imagePage > pageCount) setImagePage(pageCount);
  }, [imagePage, pageCount]);
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
      setImagePage(1);
      setPreviewIndex(undefined);
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
  const archiveMutation = useMutation({
    mutationFn: (jobId: string) => archiveBatchJob(jobId),
    onSuccess: async () => {
      setActiveJobId(undefined);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["batch-jobs"] }),
        queryClient.invalidateQueries({ queryKey: ["batch-archive"] }),
      ]);
    },
  });
  const activeJob = jobQuery.data;

  return (
    <main className="page">
      <AppTopbar title="批量 VQA" subtitle="多图像 · 多问题 · 可恢复任务" actions={<ModelSelector />} />
      <div className="page-scroll">
        <div className="batch-layout">
        <header className="page-intro">
          <div><StatusBadge>工作流配置</StatusBadge><h2>建立一组可复核的批量问答任务</h2><p>先选择图像和问题。正式创建任务后，每一个组合都会保留模型版本与独立状态。</p></div>
          <div className="batch-summary"><span><strong>{files.length}</strong>图像</span><span><strong>{questions.length}</strong>问题</span><span><strong>{files.length * questions.length}</strong>组合</span></div>
        </header>
        <section className="plain-section">
          <div className="section-heading"><div><span>01</span><h3>选择图像</h3></div></div>
          <button className="batch-dropzone" type="button" onClick={() => input.current?.click()}><UploadCloud size={22} /><strong>{files.length > 0 ? "添加图像" : "上传图像"}</strong><span>PNG、JPG、WEBP · 单张最大 10 MiB · 单批最多 {MAX_IMAGES} 张</span></button>
          <input ref={input} className="sr-only" type="file" multiple accept="image/png,image/jpeg,image/webp" onChange={(event) => {
            const selected = Array.from(event.target.files ?? []);
            setFiles((current) => {
              const known = new Set(current.map(fileKey));
              const valid = selected.filter((file) => file.size <= 10 * 1024 * 1024);
              const unique = valid.filter((file) => !known.has(fileKey(file)));
              const currentBytes = current.reduce((total, file) => total + file.size, 0);
              let acceptedBytes = currentBytes;
              const accepted: File[] = [];
              for (const file of unique) {
                if (current.length + accepted.length >= MAX_IMAGES) break;
                if (acceptedBytes + file.size > MAX_TOTAL_IMAGE_BYTES) break;
                accepted.push(file);
                acceptedBytes += file.size;
              }
              const rejectedOversize = selected.length - valid.length;
              const rejectedDuplicate = valid.length - unique.length;
              const rejectedLimit = unique.length - accepted.length;
              const reasons = [
                rejectedOversize > 0 ? `${rejectedOversize} 张超过单张 10 MiB` : "",
                rejectedDuplicate > 0 ? `${rejectedDuplicate} 张重复` : "",
                rejectedLimit > 0 ? `${rejectedLimit} 张超出 200 张或 120 MiB 单批上限` : "",
              ].filter(Boolean);
              setSelectionNotice(reasons.length > 0 ? `有 ${reasons.join("、")}，未加入任务。` : "");
              return [...current, ...accepted];
            });
            setImagePage(1);
            event.target.value = "";
          }} />
          {selectionNotice && <div className="inline-warning" role="status"><AlertTriangle size={14} />{selectionNotice}</div>}
          {files.length > 0 && (
            <div className="batch-preview-section">
              <div className="batch-preview-heading">
                <span>已选择 {files.length} / {MAX_IMAGES} 张</span>
                <button className="quiet-button" type="button" onClick={() => {
                  setFiles([]);
                  setPreviewIndex(undefined);
                  setImagePage(1);
                  setSelectionNotice("");
                }}><Trash2 size={14} />清空</button>
              </div>
              <div className="batch-thumbnail-grid" aria-label={`已选择图像，第 ${imagePage} 页`}>
                {visiblePreviews.map(({ file, url }, localIndex) => {
                  const absoluteIndex = pageStart + localIndex;
                  return (
                    <article key={fileKey(file)}>
                      <button className="batch-thumbnail" type="button" aria-label={`查看大图 ${file.name}`} onClick={() => setPreviewIndex(absoluteIndex)}>
                        <img src={url} alt="" />
                        <span><Maximize2 size={14} /></span>
                      </button>
                      <div><strong title={file.name}>{file.name}</strong><small>{(file.size / 1024).toFixed(0)} KiB</small></div>
                      <button className="thumbnail-remove" type="button" aria-label={`移除 ${file.name}`} onClick={() => {
                        setFiles((current) => current.filter((_, index) => index !== absoluteIndex));
                        setPreviewIndex(undefined);
                      }}><X size={13} /></button>
                    </article>
                  );
                })}
              </div>
              {pageCount > 1 && (
                <nav className="batch-pagination" aria-label="图像分页">
                  <button className="icon-button" type="button" aria-label="上一页" disabled={imagePage === 1} onClick={() => setImagePage((page) => page - 1)}><ChevronLeft size={16} /></button>
                  <span>第 {imagePage} / {pageCount} 页 · 每页最多 {IMAGES_PER_PAGE} 张</span>
                  <button className="icon-button" type="button" aria-label="下一页" disabled={imagePage === pageCount} onClick={() => setImagePage((page) => page + 1)}><ChevronRight size={16} /></button>
                </nav>
              )}
            </div>
          )}
        </section>
        <section className="plain-section">
          <div className="section-heading"><div><span>02</span><h3>设置问题</h3></div><button className="quiet-button" type="button" onClick={() => setQuestions([...questions, ""])}><Plus size={15} />添加问题</button></div>
          <div className="question-list">{questions.map((question, index) => <div key={index}><span>{String(index + 1).padStart(2, "0")}</span><input aria-label={`问题 ${index + 1}`} value={question} maxLength={300} onChange={(event) => setQuestions(questions.map((item, itemIndex) => itemIndex === index ? event.target.value : item))} /><button className="icon-button destructive" type="button" aria-label={`删除问题 ${index + 1}`} onClick={() => setQuestions(questions.filter((_, itemIndex) => itemIndex !== index))}><Trash2 size={15} /></button></div>)}</div>
        </section>
        {createMutation.isError && <div className="inline-error" role="alert"><AlertTriangle size={15} />{createMutation.error.message}</div>}
        {createMutation.isPending && <div className="upload-progress" aria-label={`上传进度 ${uploadProgress}%`}><span style={{ width: `${uploadProgress}%` }} /><small>正在上传并建立任务 · {uploadProgress}%</small></div>}
        {files.length * questions.length > MAX_COMBINATIONS && <div className="inline-error" role="alert"><AlertTriangle size={14} />当前共有 {files.length * questions.length} 个组合，单个任务最多 {MAX_COMBINATIONS} 个；请减少图像或问题。</div>}
        <footer className="batch-footer"><p><Layers3 size={15} />任务会逐项保存；单项失败不会丢失其他进度。</p><button className="primary-button" type="button" onClick={() => createMutation.mutate()} disabled={createMutation.isPending || files.length === 0 || questions.some((question) => !question.trim()) || files.length * questions.length > MAX_COMBINATIONS}>{createMutation.isPending ? "正在创建…" : "创建批量任务"}</button></footer>

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
                    {!["QUEUED", "RUNNING"].includes(activeJob.status) && <button className="quiet-button" type="button" disabled={archiveMutation.isPending} onClick={() => archiveMutation.mutate(activeJob.id)}><Archive size={14} />归档</button>}
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
      </div>
      <ImageLightbox
        open={Boolean(selectedPreview)}
        src={selectedPreview?.url ?? ""}
        alt={selectedPreview ? `遥感图像 ${selectedPreview.file.name}` : "遥感图像预览"}
        title={selectedPreview?.file.name}
        meta={selectedPreview ? `${(selectedPreview.file.size / 1024).toFixed(0)} KiB · 第 ${(previewIndex ?? 0) + 1} / ${files.length} 张` : undefined}
        onOpenChange={(open) => !open && setPreviewIndex(undefined)}
      />
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

function fileKey(file: File) {
  return `${file.name}:${file.size}:${file.lastModified}`;
}
