import { ArchiveRestore, FolderArchive, Layers3, MessageSquareText } from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AppTopbar } from "../components/AppChrome";
import { getArchive, listArchivedBatchJobs, restoreBatchJob, restoreConversation, restoreProject } from "../api";

export function ArchivePage() {
  const queryClient = useQueryClient();
  const archive = useQuery({ queryKey: ["archive"], queryFn: getArchive });
  const batchArchive = useQuery({ queryKey: ["batch-archive"], queryFn: listArchivedBatchJobs });
  const restore = useMutation({
    mutationFn: ({ type, id }: { type: "project" | "conversation" | "batch"; id: string }) =>
      type === "project" ? restoreProject(id) : type === "conversation" ? restoreConversation(id) : restoreBatchJob(id),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["archive"] }),
        queryClient.invalidateQueries({ queryKey: ["projects"] }),
        queryClient.invalidateQueries({ queryKey: ["batch-archive"] }),
        queryClient.invalidateQueries({ queryKey: ["batch-jobs"] }),
      ]);
    },
  });

  return (
    <main className="page">
      <AppTopbar title="归档" subtitle="恢复项目与对话" />
      <div className="page-scroll settings-layout">
        <header className="page-intro archive-intro">
          <div>
            <span className="status-badge"><FolderArchive size={12} />可恢复归档</span>
            <h2>暂存不再活跃的分析</h2>
            <p>归档不会删除影像、模型调用或审计记录。恢复项目后，才能恢复其中单独归档的对话。</p>
          </div>
        </header>

        {(archive.isPending || batchArchive.isPending) && <p className="empty-copy" aria-live="polite">正在读取归档…</p>}
        {(archive.isError || batchArchive.isError) && <p className="inline-error">{archive.error?.message ?? batchArchive.error?.message}</p>}
        {archive.data && batchArchive.data && archive.data.projects.length === 0 && archive.data.conversations.length === 0 && batchArchive.data.length === 0 && (
          <div className="archive-empty">
            <FolderArchive size={24} />
            <strong>归档中没有内容</strong>
            <p>项目或对话归档后会显示在这里，并可随时恢复。</p>
          </div>
        )}

        {archive.data && archive.data.projects.length > 0 && (
          <section className="plain-section">
            <div className="section-heading"><div><span>01</span><h3>项目</h3></div></div>
            <div className="archive-list">
              {archive.data.projects.map((project) => (
                <article key={project.id}>
                  <FolderArchive size={18} />
                  <div><strong>{project.name}</strong><small>项目 · {formatDate(project.updatedAt)}</small></div>
                  <button className="quiet-button" type="button" disabled={restore.isPending} onClick={() => restore.mutate({ type: "project", id: project.id })}>
                    <ArchiveRestore size={14} />恢复项目
                  </button>
                </article>
              ))}
            </div>
          </section>
        )}

        {archive.data && archive.data.conversations.length > 0 && (
          <section className="plain-section">
            <div className="section-heading"><div><span>02</span><h3>对话</h3></div></div>
            <div className="archive-list">
              {archive.data.conversations.map((conversation) => (
                <article key={conversation.id}>
                  <MessageSquareText size={18} />
                  <div><strong>{conversation.title}</strong><small>{conversation.projectName} · {formatDate(conversation.updatedAt)}</small></div>
                  <button className="quiet-button" type="button" disabled={restore.isPending} onClick={() => restore.mutate({ type: "conversation", id: conversation.id })}>
                    <ArchiveRestore size={14} />恢复对话
                  </button>
                </article>
              ))}
            </div>
          </section>
        )}

        {batchArchive.data && batchArchive.data.length > 0 && (
          <section className="plain-section">
            <div className="section-heading"><div><span>03</span><h3>批量任务</h3></div></div>
            <div className="archive-list">
              {batchArchive.data.map((job) => (
                <article key={job.id}>
                  <Layers3 size={18} />
                  <div><strong>批量任务 {job.id.slice(0, 8)}</strong><small>{job.totalItems} 项 · {job.status} · {formatDate(job.updatedAt)}</small></div>
                  <button className="quiet-button" type="button" disabled={restore.isPending} onClick={() => restore.mutate({ type: "batch", id: job.id })}>
                    <ArchiveRestore size={14} />恢复任务
                  </button>
                </article>
              ))}
            </div>
          </section>
        )}
      </div>
    </main>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
