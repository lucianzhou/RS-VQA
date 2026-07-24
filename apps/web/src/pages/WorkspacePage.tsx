import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  ArrowUp,
  Bot,
  Check,
  FileImage,
  FileDown,
  ImagePlus,
  Info,
  LoaderCircle,
  Maximize2,
  Paperclip,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  StopCircle,
  Trash2,
  UploadCloud,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import {
  askConversation,
  deleteConversationImage,
  getConversation,
  uploadConversationImage,
  runTrustedAgentStream,
} from "../api";
import { AppTopbar, ModelSelector } from "../components/AppChrome";
import { useWorkspaceStore } from "../store";
import type { ImageAsset, PersistedMessage } from "../types";

const questionSchema = z.object({
  question: z.string().trim().min(1, "请输入问题。").max(300, "问题不能超过 300 个字符。"),
});
type QuestionForm = z.infer<typeof questionSchema>;
const examples = ["图中有没有道路？", "图中有多少建筑物？", "建筑物覆盖面积是多少？"];

export function WorkspacePage() {
  const queryClient = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);
  const activeConversationId = useWorkspaceStore((state) => state.activeConversationId);
  const [fileError, setFileError] = useState("");
  const [optimisticQuestion, setOptimisticQuestion] = useState("");
  const [controller, setController] = useState<AbortController>();
  const [agentOpen, setAgentOpen] = useState(false);
  const [agentQuestion, setAgentQuestion] = useState("这个模型支持哪些问题？");
  const [agentStage, setAgentStage] = useState("");
  const [agentController, setAgentController] = useState<AbortController>();
  const conversationQuery = useQuery({
    queryKey: ["conversation", activeConversationId],
    queryFn: () => getConversation(activeConversationId!),
    enabled: Boolean(activeConversationId),
  });
  const form = useForm<QuestionForm>({
    resolver: zodResolver(questionSchema),
    defaultValues: { question: "" },
  });

  useEffect(() => () => controller?.abort(), [controller]);
  useEffect(() => () => agentController?.abort(), [agentController]);

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["conversation", activeConversationId] }),
      queryClient.invalidateQueries({ queryKey: ["projects"] }),
    ]);
  };

  const uploadMutation = useMutation({
    mutationFn: (file: File) => uploadConversationImage(activeConversationId!, file),
    onSuccess: refresh,
    onError: (error) => setFileError(error.message),
  });
  const deleteMutation = useMutation({
    mutationFn: () => deleteConversationImage(activeConversationId!),
    onSuccess: refresh,
    onError: (error) => setFileError(error.message),
  });
  const questionMutation = useMutation({
    mutationFn: ({ question, signal }: { question: string; signal: AbortSignal }) =>
      askConversation(activeConversationId!, question, undefined, signal),
    onSuccess: refresh,
    onSettled: () => {
      setController(undefined);
      setOptimisticQuestion("");
    },
  });
  const agentMutation = useMutation({
    mutationFn: ({ signal }: { signal: AbortSignal }) =>
      runTrustedAgentStream(agentQuestion, activeConversationId ?? undefined, setAgentStage, signal),
    onSettled: () => setAgentController(undefined),
  });

  const selectImage = (file?: File) => {
    if (!file) return;
    if (!["image/png", "image/jpeg", "image/webp"].includes(file.type)) {
      setFileError("请选择 PNG、JPG 或 WEBP 图像。");
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setFileError("图像不能超过 10 MiB。");
      return;
    }
    setFileError("");
    uploadMutation.mutate(file);
  };

  const submit = form.handleSubmit(({ question }) => {
    if (!conversationQuery.data?.image) {
      setFileError("请先上传一张遥感图像。");
      return;
    }
    const requestController = new AbortController();
    setController(requestController);
    setOptimisticQuestion(question);
    form.reset();
    questionMutation.mutate({ question, signal: requestController.signal });
  });

  if (!activeConversationId || conversationQuery.isPending) {
    return <WorkspaceLoading />;
  }
  if (conversationQuery.isError) {
    return <WorkspaceError message={conversationQuery.error.message} onRetry={() => conversationQuery.refetch()} />;
  }
  const conversation = conversationQuery.data;

  return (
    <main className={`workspace-page ${agentOpen ? "agent-open" : ""}`}>
      <AppTopbar
        title={conversation.title}
        subtitle={conversation.image ? "影像上下文已持久化" : "新分析"}
        actions={<><a className="agent-toggle" href={`/api/v1/conversations/${activeConversationId}/report`}><FileDown size={15} />导出记录</a><button className={`agent-toggle ${agentOpen ? "is-active" : ""}`} type="button" onClick={() => setAgentOpen(!agentOpen)}><Sparkles size={15} />可信 Agent</button><ModelSelector /></>}
      />
      <section className="conversation-canvas">
        {!conversation.image ? (
          <WelcomeUpload
            pending={uploadMutation.isPending}
            onChoose={() => fileInput.current?.click()}
            onDrop={selectImage}
          />
        ) : (
          <>
            <ImageContext
              image={conversation.image}
              pending={uploadMutation.isPending || deleteMutation.isPending}
              onReplace={() => fileInput.current?.click()}
              onRemove={() => deleteMutation.mutate()}
            />
            <div className="messages" aria-live="polite">
              {conversation.messages.length === 0 && !optimisticQuestion ? (
                <StarterPrompt onSelect={(question) => form.setValue("question", question, { shouldValidate: true })} />
              ) : (
                <>
                  {conversation.messages.map((message) => <Message key={message.id} message={message} />)}
                  {optimisticQuestion && (
                    <>
                      <article className="message user-message"><div>{optimisticQuestion}</div></article>
                      <PendingMessage />
                    </>
                  )}
                </>
              )}
            </div>
          </>
        )}
      </section>
      <input
        ref={fileInput}
        className="sr-only"
        type="file"
        accept=".png,.jpg,.jpeg,.webp,image/png,image/jpeg,image/webp"
        onChange={(event) => {
          selectImage(event.target.files?.[0]);
          event.target.value = "";
        }}
        aria-label="选择遥感图像"
      />
      {(fileError || questionMutation.isError) && (
        <div className="floating-error" role="alert">
          <AlertTriangle size={15} />{fileError || questionMutation.error?.message}
        </div>
      )}
      {agentOpen && (
        <aside className="agent-drawer" aria-label="可信 Agent">
          <header><span><Bot size={16} />可信 Agent</span><button className="icon-button" type="button" aria-label="关闭 Agent" onClick={() => setAgentOpen(false)}>×</button></header>
          <p>只读工具编排，用于解释模型边界、版本和历史；不会修改模型原始答案。</p>
          <div className="agent-suggestions">
            {["这个模型支持哪些问题？", "查询当前模型版本", "检查系统健康状态", "读取当前会话历史"].map((item) => <button type="button" key={item} onClick={() => setAgentQuestion(item)}>{item}</button>)}
          </div>
          <form onSubmit={(event) => {
            event.preventDefault();
            if (!agentQuestion.trim()) return;
            const requestController = new AbortController();
            setAgentController(requestController);
            setAgentStage("accepted");
            agentMutation.mutate({ signal: requestController.signal });
          }}>
            <textarea aria-label="向可信 Agent 提问" value={agentQuestion} maxLength={500} onChange={(event) => setAgentQuestion(event.target.value)} />
            {agentMutation.isPending ? (
              <button className="primary-button" type="button" onClick={() => agentController?.abort()}>取消 · {agentStage === "tool_started" ? "工具执行中" : "建立流…"}</button>
            ) : (
              <button className="primary-button" type="submit">运行只读工具</button>
            )}
          </form>
          {agentMutation.isError && <div className="inline-error"><AlertTriangle size={14} />{agentMutation.error.message}</div>}
          {agentMutation.data && (
            <article className="agent-result">
              <span>AGENT 解释</span><p>{agentMutation.data.answer}</p>
              <details><summary>工具调用与审计</summary>
                {agentMutation.data.toolCalls.map((call) => <dl key={call.id}><div><dt>工具</dt><dd>{call.name}</dd></div><div><dt>状态</dt><dd>{call.status} · {call.latencyMs} ms</dd></div><div><dt>Trace</dt><dd>{agentMutation.data.traceId}</dd></div></dl>)}
              </details>
              <small>{agentMutation.data.boundaryNotice}</small>
            </article>
          )}
        </aside>
      )}
      <footer className="composer-wrap">
        <form className="composer" onSubmit={submit}>
          <button className="composer-icon" type="button" aria-label="上传或更换图像" title="上传或更换图像" onClick={() => fileInput.current?.click()}><Paperclip size={19} /></button>
          <label className="sr-only" htmlFor="question">向当前影像提问</label>
          <textarea
            id="question"
            rows={1}
            maxLength={300}
            placeholder={conversation.image ? "问问这张遥感图像…" : "请先上传一张遥感图像"}
            disabled={!conversation.image || questionMutation.isPending}
            {...form.register("question")}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                void submit();
              }
            }}
          />
          {questionMutation.isPending ? (
            <button className="send-button stop" type="button" aria-label="取消分析" title="取消分析" onClick={() => controller?.abort()}><StopCircle size={18} /></button>
          ) : (
            <button className="send-button" type="submit" aria-label="发送问题" title="发送问题" disabled={!conversation.image}><ArrowUp size={18} /></button>
          )}
        </form>
        <div className="composer-caption">
          <span><ShieldCheck size={13} />闭集研究模型会拒绝超出已验证范围的问题</span>
          {form.formState.errors.question && <span className="field-error">{form.formState.errors.question.message}</span>}
        </div>
      </footer>
    </main>
  );
}

function WorkspaceLoading() {
  return (
    <main className="workspace-page">
      <AppTopbar title="正在加载工作区" />
      <div className="workspace-center"><LoaderCircle className="spin" size={20} /><span>恢复项目与会话…</span></div>
    </main>
  );
}

function WorkspaceError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <main className="workspace-page">
      <AppTopbar title="工作区暂不可用" />
      <div className="workspace-center error"><AlertTriangle size={20} /><strong>无法恢复会话</strong><span>{message}</span><button className="primary-button" type="button" onClick={onRetry}>重试</button></div>
    </main>
  );
}

function WelcomeUpload({ pending, onChoose, onDrop }: { pending: boolean; onChoose: () => void; onDrop: (file?: File) => void }) {
  return (
    <div className="welcome">
      <div className="welcome-symbol">{pending ? <LoaderCircle className="spin" size={25} /> : <ImagePlus size={28} strokeWidth={1.6} />}</div>
      <p className="eyebrow">REMOTE SENSING VISUAL QA</p>
      <h2>{pending ? "正在安全保存影像" : "从一张遥感图像开始"}</h2>
      <p className="welcome-copy">上传影像后直接输入问题，并围绕同一张图继续多轮分析。</p>
      <button
        className="upload-zone"
        type="button"
        disabled={pending}
        onClick={onChoose}
        onDragOver={(event) => event.preventDefault()}
        onDrop={(event) => {
          event.preventDefault();
          onDrop(event.dataTransfer.files?.[0]);
        }}
      >
        <UploadCloud size={22} /><strong>选择图像</strong><span>或拖放至此 · PNG、JPG、WEBP · 最大 10 MiB</span>
      </button>
      <div className="scope-line"><span>已验证问题</span><i>存在</i><i>数量</i><i>面积</i><i>比较</i></div>
    </div>
  );
}

function ImageContext({ image, pending, onReplace, onRemove }: { image: ImageAsset; pending: boolean; onReplace: () => void; onRemove: () => void }) {
  return (
    <article className="image-context">
      <img src={image.contentUrl} alt="当前分析的遥感图像" />
      <div>
        <p>当前影像</p><strong>{image.originalName}</strong>
        <span>{`${image.width} × ${image.height} · ${formatBytes(image.sizeBytes)} · ${image.mimeType.replace("image/", "").toUpperCase()}`}</span>
      </div>
      <div className="image-actions">
        <a className="quiet-button" href={image.contentUrl} target="_blank" rel="noreferrer"><Maximize2 size={14} />查看大图</a>
        <button className="quiet-button" type="button" disabled={pending} onClick={onReplace}><RefreshCw size={14} />更换影像</button>
        <button className="icon-button destructive" type="button" disabled={pending} aria-label="移除影像" title="移除影像" onClick={onRemove}><Trash2 size={16} /></button>
      </div>
    </article>
  );
}

function StarterPrompt({ onSelect }: { onSelect: (question: string) => void }) {
  return (
    <section className="starter">
      <span className="assistant-avatar">RS</span>
      <div><strong>影像已就绪</strong><p>输入一个问题，或从已验证的问法开始。</p><div className="starter-questions">{examples.map((question) => <button type="button" key={question} onClick={() => onSelect(question)}>{question}</button>)}</div></div>
    </section>
  );
}

function PendingMessage() {
  return <article className="message assistant-message"><span className="assistant-avatar">RS</span><div className="answer-body pending-answer"><LoaderCircle className="spin" size={17} /><span>正在分析当前影像…</span></div></article>;
}

function Message({ message }: { message: PersistedMessage }) {
  if (message.role === "user") return <article className="message user-message"><div>{message.content}</div></article>;
  const invocation = message.invocation;
  const isMock = message.sourceType === "MOCK";
  const answered = invocation?.status === "answered";
  const lowConfidence = answered && invocation?.confidence != null && invocation.confidence < 0.65;
  const notice = metadataNotice(message.metadataJson);
  return (
    <article className="message assistant-message">
      <span className={`assistant-avatar ${answered ? "" : "warning"}`}>{answered ? "RS" : <AlertTriangle size={15} />}</span>
      <div className="answer-body">
        <div className="answer-heading">
          <span className={`result-state ${answered && !lowConfidence ? "success" : "warning"}`}>
            {answered && !lowConfidence ? <Check size={14} /> : <Info size={14} />}
            {lowConfidence ? "低置信度，请复核" : answered ? "模型回答" : "超出能力范围"}
          </span>
          {isMock && <span className="mock-flag">MOCK</span>}
        </div>
        {answered && <p className="answer-value">{message.content}</p>}
        {lowConfidence && <p className="capability-notice">模型置信度低于 65% 展示阈值。答案保持原样，但不建议直接作为确定结论。</p>}
        {!answered && <p className="capability-notice">{message.content}</p>}
        {notice && <p className="capability-notice">{notice}</p>}
        {invocation && (
          <details className="provenance">
            <summary>查看模型与调用信息</summary>
            <dl>
              <div><dt>输出来源</dt><dd>{originLabel(invocation.predictionOrigin)}</dd></div>
              <div><dt>发布版本</dt><dd>{invocation.modelReleaseId ?? "无"}</dd></div>
              {invocation.confidence != null && <div><dt>置信度</dt><dd>{(invocation.confidence * 100).toFixed(1)}%</dd></div>}
              {invocation.latencyMs != null && <div><dt>模型耗时</dt><dd>{invocation.latencyMs} ms</dd></div>}
              <div><dt>请求编号</dt><dd>{invocation.requestId}</dd></div>
            </dl>
          </details>
        )}
      </div>
    </article>
  );
}

function metadataNotice(metadata: string | null) {
  if (!metadata) return "";
  try {
    return (JSON.parse(metadata) as { capabilityNotice?: string }).capabilityNotice ?? "";
  } catch {
    return "";
  }
}

function originLabel(origin: string) {
  if (origin === "research_vilt_predicted_soft") return "研究 ViLT predicted-soft";
  if (origin === "external_vlm_assist") return "外部通用视觉模型辅助";
  if (origin === "mock_demo") return "Mock 演示，不是研究结果";
  return "不适用";
}

function formatBytes(bytes: number) {
  return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(0)} KiB` : `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
}
