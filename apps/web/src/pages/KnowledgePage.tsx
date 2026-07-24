import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, BookOpen, Database, FileText, LoaderCircle, Search, ShieldCheck, Trash2, Upload } from "lucide-react";
import { useRef, useState } from "react";
import { AppTopbar, StatusBadge } from "../App";
import { deleteKnowledgeDocument, listKnowledgeDocuments, searchKnowledge, seedApprovedKnowledge, uploadKnowledgeDocument } from "../api";

export function KnowledgePage() {
  const input = useRef<HTMLInputElement>(null);
  const queryClient = useQueryClient();
  const [query, setQuery] = useState("predicted-soft 模型的能力边界和已核准指标是什么？");
  const documents = useQuery({ queryKey: ["knowledge-documents"], queryFn: listKnowledgeDocuments });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["knowledge-documents"] });
  const upload = useMutation({ mutationFn: uploadKnowledgeDocument, onSuccess: refresh });
  const seed = useMutation({ mutationFn: seedApprovedKnowledge, onSuccess: refresh });
  const remove = useMutation({ mutationFn: deleteKnowledgeDocument, onSuccess: refresh });
  const search = useMutation({ mutationFn: searchKnowledge });
  const error = upload.error ?? seed.error ?? remove.error ?? search.error;

  return (
    <main className="page">
      <AppTopbar title="知识库" subtitle="BGE Embedding · Milvus · 来源引用" />
      <div className="page-scroll settings-layout">
        <header className="page-intro">
          <div><StatusBadge>引用优先的检索</StatusBadge><h2>让 Agent 依据已核准资料解释，而不是凭空补全</h2><p>知识检索只解释模型、系统与遥感 VQA 基础资料，不会替代图像分类推理。</p></div>
          <button className="primary-button" type="button" disabled={seed.isPending} onClick={() => seed.mutate()}><ShieldCheck size={14} />{seed.isPending ? "正在建立索引…" : "导入已核准边界"}</button>
        </header>
        <section className="plain-section">
          <div className="section-heading"><div><span>01</span><h3>索引文档</h3></div><button className="quiet-button" type="button" onClick={() => input.current?.click()}><Upload size={14} />导入 Markdown / TXT</button></div>
          <input ref={input} className="sr-only" type="file" accept=".md,.txt,text/markdown,text/plain" onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) upload.mutate(file);
            event.target.value = "";
          }} />
          {documents.isPending ? <div className="workspace-center compact"><LoaderCircle className="spin" size={16} />正在读取索引…</div> : (
            <div className="knowledge-documents">
              {(documents.data ?? []).map((document) => <article key={document.id}>
                <span><FileText size={17} /></span>
                <div><strong>{document.title}</strong><small>{document.indexVersion} · {document.status}{document.errorMessage ? ` · ${document.errorMessage}` : ""}</small></div>
                <button className="icon-button destructive" type="button" aria-label={`删除 ${document.title}`} onClick={() => remove.mutate(document.id)}><Trash2 size={14} /></button>
              </article>)}
              {documents.data?.length === 0 && <p className="empty-copy"><BookOpen size={17} />尚未建立知识索引。启动 RAG Profile 后可先导入内置已核准资料。</p>}
            </div>
          )}
        </section>
        <section className="plain-section">
          <div className="section-heading"><div><span>02</span><h3>检索与来源核验</h3></div></div>
          <form className="knowledge-search" onSubmit={(event) => { event.preventDefault(); if (query.trim()) search.mutate(query); }}>
            <Search size={16} /><input aria-label="知识检索问题" value={query} onChange={(event) => setQuery(event.target.value)} maxLength={500} /><button className="primary-button" type="submit" disabled={search.isPending}>{search.isPending ? "检索中…" : "检索"}</button>
          </form>
          {error && <div className="inline-error" role="alert"><AlertTriangle size={14} />{error.message}</div>}
          {search.data && <div className="citation-results">
            <header><span><Database size={14} />{search.data.embeddingModel}</span><small>{search.data.citations.length} 条引用 · {search.data.latencyMs} ms · {search.data.requestId}</small></header>
            {search.data.citations.length === 0 ? <p className="empty-copy">没有达到相似度阈值的来源，不生成无引用结论。</p> : search.data.citations.map((citation) => <article key={`${citation.documentId}-${citation.chunkIndex}`}>
              <div><strong>{citation.title}</strong><span>分块 {citation.chunkIndex + 1} · 相似度 {(citation.score * 100).toFixed(1)}%</span></div>
              <p>{citation.content}</p><small>{citation.indexVersion} · document {citation.documentId}</small>
            </article>)}
          </div>}
        </section>
      </div>
    </main>
  );
}
