import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  ArrowUp,
  Bot,
  Check,
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
import { AnimatePresence, motion } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import {
  askConversation,
  deleteConversationImage,
  getConversation,
  uploadConversationImage,
} from "../api";
import { AppTopbar, ModelSelector, ProviderAvatar } from "../components/AppChrome";
import { ImageLightbox } from "../components/ImageLightbox";
import { RsBotChat } from "../components/RsBotChat";
import { RS_BOT_NAME, RS_BOT_SUBTITLE, toTranscriptRun, useRsBotSession } from "../rsbot";
import { useWorkspaceStore } from "../store";
import type { AgentHistoryRun, ImageAsset, PersistedMessage } from "../types";

const questionSchema = z.object({
  question: z.string().trim().min(1, "请输入问题。").max(300, "问题不能超过 300 个字符。"),
});
type QuestionForm = z.infer<typeof questionSchema>;
const examples = ["图中有没有道路？", "图中有多少建筑物？", "建筑物覆盖面积是多少？"];

export function WorkspacePage() {
  const queryClient = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);
  const activeConversationId = useWorkspaceStore((state) => state.activeConversationId);
  const selectedModelId = useWorkspaceStore((state) => state.selectedModelId);
  const [fileError, setFileError] = useState("");
  const [optimisticQuestion, setOptimisticQuestion] = useState("");
  const [controller, setController] = useState<AbortController>();
  const [agentOpen, setAgentOpen] = useState(false);
  const [imagePreviewOpen, setImagePreviewOpen] = useState(false);
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
      askConversation(activeConversationId!, question, selectedModelId, undefined, signal),
    onSuccess: refresh,
    onSettled: () => {
      setController(undefined);
      setOptimisticQuestion("");
    },
  });
  // Same hook, same session and same endpoint as the standalone RS-Bot page,
  // so the drawer can never become a second agent with its own history.
  const rsBot = useRsBotSession({ conversationId: activeConversationId ?? undefined });
  const [drawerRuns, setDrawerRuns] = useState<AgentHistoryRun[]>([]);
  useEffect(() => {
    if (rsBot.lastRun) {
      setDrawerRuns((current) => current.some((item) => item.runId === rsBot.lastRun!.runId)
        ? current
        : [...current, toTranscriptRun(rsBot.lastRun!, rsBot.pendingQuestion || lastAskRef.current)]);
    }
  }, [rsBot.lastRun, rsBot.pendingQuestion]);
  const lastAskRef = useRef("");
  useEffect(() => {
    if (rsBot.pendingQuestion) lastAskRef.current = rsBot.pendingQuestion;
  }, [rsBot.pendingQuestion]);
  useEffect(() => setDrawerRuns([]), [activeConversationId]);

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
        actions={<><a className="agent-toggle" href={`/api/v1/conversations/${activeConversationId}/report`}><FileDown size={15} />导出记录</a><button className={`agent-toggle ${agentOpen ? "is-active" : ""}`} type="button" onClick={() => setAgentOpen(!agentOpen)}><Sparkles size={15} />{RS_BOT_NAME}</button><ModelSelector /></>}
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
              onPreview={() => setImagePreviewOpen(true)}
              onReplace={() => fileInput.current?.click()}
              onRemove={() => deleteMutation.mutate()}
            />
            <div className="messages" aria-live="polite">
              {conversation.messages.length === 0 && !optimisticQuestion ? (
                <StarterPrompt onSelect={(question) => form.setValue("question", question, { shouldValidate: true })} />
              ) : (
                <>
                  {conversation.messages.map((message) => (
                    <Message
                      key={message.id}
                      message={message}
                      onSelect={(question) => form.setValue("question", question, { shouldValidate: true })}
                    />
                  ))}
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
      <AnimatePresence>
        {agentOpen && (
        <motion.aside
          className="agent-drawer"
          aria-label={RS_BOT_NAME}
          initial={{ opacity: 0, x: 28, scale: 0.985 }}
          animate={{ opacity: 1, x: 0, scale: 1 }}
          exit={{ opacity: 0, x: 18, scale: 0.99 }}
          transition={{ type: "spring", stiffness: 430, damping: 36, mass: 0.8 }}
        >
          <header>
            <span><Bot size={16} />{RS_BOT_NAME}<small>{RS_BOT_SUBTITLE}</small></span>
            <button className="icon-button" type="button" aria-label={`关闭 ${RS_BOT_NAME}`} onClick={() => setAgentOpen(false)}>×</button>
          </header>
          <RsBotChat
            compact
            runs={rsBot.runs.length > 0 ? rsBot.runs : drawerRuns}
            isRunning={rsBot.isRunning}
            stage={rsBot.stage}
            pendingQuestion={rsBot.pendingQuestion}
            error={rsBot.error}
            placeholder="询问模型版本、能力边界或当前会话记录…"
            suggestions={["这个模型支持哪些问题？", "查询当前模型版本", "检查系统健康状态", "读取当前会话历史"]}
            onAsk={rsBot.ask}
            onCancel={rsBot.cancel}
          />
        </motion.aside>
        )}
      </AnimatePresence>
      {conversation.image && (
        <ImageLightbox
          open={imagePreviewOpen}
          src={conversation.image.contentUrl}
          alt={`遥感图像 ${conversation.image.originalName}`}
          title={conversation.image.originalName}
          meta={`${conversation.image.width} × ${conversation.image.height} · ${formatBytes(conversation.image.sizeBytes)}`}
          onOpenChange={setImagePreviewOpen}
        />
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

function ImageContext({
  image,
  pending,
  onPreview,
  onReplace,
  onRemove,
}: {
  image: ImageAsset;
  pending: boolean;
  onPreview: () => void;
  onReplace: () => void;
  onRemove: () => void;
}) {
  return (
    <motion.article
      className="image-context"
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.26, ease: [0.22, 1, 0.36, 1] }}
    >
      <img src={image.contentUrl} alt="当前分析的遥感图像" />
      <div>
        <p>当前影像</p><strong>{image.originalName}</strong>
        <span>{`${image.width} × ${image.height} · ${formatBytes(image.sizeBytes)} · ${image.mimeType.replace("image/", "").toUpperCase()}`}</span>
      </div>
      <div className="image-actions">
        <button className="quiet-button" type="button" onClick={onPreview}><Maximize2 size={14} />查看大图</button>
        <button className="quiet-button" type="button" disabled={pending} onClick={onReplace}><RefreshCw size={14} />更换影像</button>
        <button className="icon-button destructive" type="button" disabled={pending} aria-label="移除影像" title="移除影像" onClick={onRemove}><Trash2 size={16} /></button>
      </div>
    </motion.article>
  );
}

function StarterPrompt({ onSelect }: { onSelect: (question: string) => void }) {
  return (
    <motion.section className="starter" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}>
      <span className="assistant-avatar">RS</span>
      <div><strong>影像已就绪</strong><p>输入一个问题，或从已验证的问法开始。</p><div className="starter-questions">{examples.map((question) => <button type="button" key={question} onClick={() => onSelect(question)}>{question}</button>)}</div></div>
    </motion.section>
  );
}

function PendingMessage() {
  return <motion.article className="message assistant-message" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}><span className="assistant-avatar">RS</span><div className="answer-body pending-answer"><LoaderCircle className="spin" size={17} /><span>正在分析当前影像…</span></div></motion.article>;
}

function Message({ message, onSelect }: { message: PersistedMessage; onSelect?: (question: string) => void }) {
  if (message.role === "user") return <motion.article className="message user-message" initial={{ opacity: 0, y: 7 }} animate={{ opacity: 1, y: 0 }}><div>{message.content}</div></motion.article>;
  const invocation = message.invocation;
  const isMock = message.sourceType === "MOCK";
  const isExternal = message.sourceType === "EXTERNAL_VLM";
  const answered = invocation?.status === "answered";
  const lowConfidence = answered && invocation?.confidence != null && invocation.confidence < 0.65;
  const metadata = messageMetadata(message.metadataJson);
  const providerName = externalModelName(metadata.providerId, invocation?.providerModel);
  const needsReview = answered && metadata.requiresReview;
  const provisional = answered && !isExternal && metadata.scopeVerification === "provisional";
  const answerLabel = needsReview ? "答案形式异常，请复核"
    : lowConfidence ? "低置信度，请复核"
    : answered ? (isExternal ? `${providerName} 回答` : "模型回答")
    : metadata.needsClarification ? "需要补充说明"
    : "超出能力范围";
  const avatarKind = isExternal ? "EXTERNAL_VLM" : isMock ? "MOCK" : "RESEARCH_MODEL";
  return (
    <motion.article className="message assistant-message" initial={{ opacity: 0, y: 9 }} animate={{ opacity: 1, y: 0 }}>
      {answered
        ? <ProviderAvatar providerId={metadata.providerId} kind={avatarKind} size={28} />
        : <span className="avatar-icon warning" style={{ width: 28, height: 28 }}><AlertTriangle size={15} /></span>}
      <div className="answer-body">
        <div className="answer-heading">
          <span className={`result-state ${answered && !lowConfidence && !needsReview ? "success" : "warning"}`}>
            {answered && !lowConfidence && !needsReview ? <Check size={14} /> : <Info size={14} />}
            {answerLabel}
          </span>
          {isMock && <span className="mock-flag">MOCK</span>}
          {isExternal && <span className="external-flag">{providerName}</span>}
        </div>
        {metadata.interpretationNote && <p className="canonical-hint">{metadata.interpretationNote}</p>}
        {answered && <p className="answer-value">{message.content}</p>}
        {answered && metadata.displayAnswer && metadata.displayAnswer !== message.content && (
          <p className="answer-display">{metadata.displayAnswer}</p>
        )}
        {lowConfidence && <p className="capability-notice">模型置信度低于 65% 展示阈值。答案保持原样，但不建议直接作为确定结论。</p>}
        {provisional && <p className="capability-notice">该地物与题型组合仍在核验中，结果仅供参考。</p>}
        {!answered && <p className="capability-notice">{message.content}</p>}
        {metadata.clarificationOptions.length > 0 && (
          <div className="clarification-options">
            {metadata.clarificationOptions.map((option) => (
              <button type="button" key={option} onClick={() => onSelect?.(option)}>{option}</button>
            ))}
          </div>
        )}
        {metadata.notice && <p className="capability-notice">{metadata.notice}</p>}
        {invocation && (
          <details className="provenance">
            <summary>查看模型与调用信息</summary>
            <dl>
              <div><dt>输出来源</dt><dd>{originLabel(invocation.predictionOrigin, providerName)}</dd></div>
              {invocation.providerModel && <div><dt>Provider 模型</dt><dd>{invocation.providerModel}</dd></div>}
              {!isExternal && <div><dt>发布版本</dt><dd>{invocation.modelReleaseId ?? "无"}</dd></div>}
              {metadata.modelInputQuestion && <div><dt>模型输入问题</dt><dd>{metadata.modelInputQuestion}</dd></div>}
              {metadata.normalizerVersion && <div><dt>问题规范化版本</dt><dd>{metadata.normalizerVersion}</dd></div>}
              {metadata.matchedIntent && <div><dt>识别题型</dt><dd>{intentLabel(metadata.matchedIntent)}</dd></div>}
              {invocation.confidence != null && <div><dt>置信度</dt><dd>{(invocation.confidence * 100).toFixed(1)}%</dd></div>}
              {invocation.totalTokens != null && <div><dt>Token 用量</dt><dd>{invocation.totalTokens}（输入 {invocation.promptTokens ?? "?"} / 输出 {invocation.completionTokens ?? "?"}）</dd></div>}
              {invocation.latencyMs != null && <div><dt>模型耗时</dt><dd>{invocation.latencyMs} ms</dd></div>}
              <div><dt>请求编号</dt><dd>{invocation.requestId}</dd></div>
            </dl>
          </details>
        )}
      </div>
    </motion.article>
  );
}

interface MessageMetadata {
  notice: string;
  requiresReview: boolean;
  providerId: string;
  /** Localized rendering of the raw answer. Never replaces it. */
  displayAnswer: string;
  /** "已理解为：…" hint, present only when the question was rewritten. */
  interpretationNote: string;
  needsClarification: boolean;
  clarificationOptions: string[];
  canonicalQuestion: string;
  modelInputQuestion: string;
  normalizerVersion: string;
  matchedIntent: string;
  scopeVerification: string;
}

const EMPTY_METADATA: MessageMetadata = {
  notice: "",
  requiresReview: false,
  providerId: "",
  displayAnswer: "",
  interpretationNote: "",
  needsClarification: false,
  clarificationOptions: [],
  canonicalQuestion: "",
  modelInputQuestion: "",
  normalizerVersion: "",
  matchedIntent: "",
  scopeVerification: "",
};

function messageMetadata(metadata: string | null): MessageMetadata {
  if (!metadata) return EMPTY_METADATA;
  try {
    const value = JSON.parse(metadata) as Record<string, unknown>;
    return {
      notice: (value.outputBoundary as string) ?? (value.capabilityNotice as string) ?? "",
      requiresReview: value.requiresReview === true,
      providerId: (value.providerId as string) ?? "",
      displayAnswer: (value.displayAnswer as string) ?? "",
      interpretationNote: (value.interpretationNote as string) ?? "",
      needsClarification: value.needsClarification === true,
      clarificationOptions: Array.isArray(value.clarificationOptions) ? (value.clarificationOptions as string[]) : [],
      canonicalQuestion: (value.canonicalQuestion as string) ?? "",
      modelInputQuestion: (value.modelInputQuestion as string) ?? "",
      normalizerVersion: (value.normalizerVersion as string) ?? "",
      matchedIntent: (value.matchedIntent as string) ?? "",
      scopeVerification: (value.scopeVerification as string) ?? "",
    };
  } catch {
    return EMPTY_METADATA;
  }
}

function intentLabel(intent: string) {
  if (intent === "presence") return "存在性";
  if (intent === "count") return "数量";
  if (intent === "area") return "面积";
  if (intent === "comparison") return "比较";
  return intent;
}

function externalModelName(providerId: string, modelId?: string | null) {
  if (providerId === "gemini") {
    return modelId?.startsWith("gemini-") ? `Gemini-${modelId.slice("gemini-".length)}` : "Gemini";
  }
  if (providerId === "qwen") return "Qwen3-VL 32B";
  return modelId || "已配置模型";
}

function originLabel(origin: string, providerName: string) {
  if (origin === "research_vilt_predicted_soft") return "研究 ViLT predicted-soft";
  if (origin === "external_vlm_assist") return `${providerName} 辅助`;
  if (origin === "mock_demo") return "Mock 演示，不是研究结果";
  return "不适用";
}

function formatBytes(bytes: number) {
  return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(0)} KiB` : `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
}
