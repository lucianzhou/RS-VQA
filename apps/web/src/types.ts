export type PredictionOrigin =
  | "mock_demo"
  | "research_vilt_predicted_soft"
  | "external_vlm_assist"
  | "not_applicable";

export interface PredictionResponse {
  requestId: string;
  status: "answered" | "unsupported" | "model_unavailable";
  supported: boolean;
  answer: string | null;
  canonicalQuestion: string | null;
  questionType: string | null;
  predictionOrigin: PredictionOrigin;
  modelReleaseId: string | null;
  capabilityNotice: string;
  confidence?: number | null;
  margin?: number | null;
  latencyMs?: number | null;
}

export interface ApiError {
  code: string;
  message: string;
  requestId?: string;
  retryable?: boolean;
}

export interface ConversationMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  createdAt: string;
  result?: PredictionResponse;
  pending?: boolean;
  error?: string;
}

export interface Conversation {
  id: string;
  title: string;
  imageName?: string;
  imagePreview?: string;
  messages: ConversationMessage[];
  updatedAt: string;
}

export interface Project {
  id: string;
  name: string;
  conversations: ConversationSummary[];
  updatedAt: string;
}

export interface ConversationSummary {
  id: string;
  title: string;
  hasImage: boolean;
  updatedAt: string;
}

export interface ImageAsset {
  id: string;
  originalName: string;
  sha256: string;
  mimeType: string;
  sizeBytes: number;
  width: number;
  height: number;
  contentUrl: string;
}

export interface PersistedInvocation {
  id: string;
  requestId: string;
  status: string;
  predictionOrigin: PredictionOrigin;
  modelReleaseId: string | null;
  confidence: number | null;
  margin: number | null;
  latencyMs: number | null;
}

export interface PersistedMessage {
  id: string;
  role: "user" | "assistant";
  sourceType: "USER" | "RESEARCH_MODEL" | "MOCK" | "EXTERNAL_VLM" | "AGENT_EXPLANATION";
  content: string;
  metadataJson: string | null;
  invocation: PersistedInvocation | null;
  createdAt: string;
}

export interface ConversationDetail {
  id: string;
  projectId: string;
  title: string;
  image: ImageAsset | null;
  messages: PersistedMessage[];
  createdAt: string;
  updatedAt: string;
}

export interface CurrentUser {
  id: string;
  username: string;
  displayName: string;
  role: "USER" | "ADMIN";
  demo: boolean;
}

export interface QuestionApiResponse {
  userMessage: PersistedMessage;
  assistantMessage: PersistedMessage;
  result: PredictionResponse;
}

export interface ModelOption {
  id: string;
  name: string;
  description: string;
  kind: "RESEARCH_MODEL" | "EXTERNAL_VLM" | "MOCK";
  configured: boolean;
  releaseId?: string;
}

export interface ProviderDescriptor {
  providerId: string;
  modelId: string;
  displayName: string;
  kind: "RESEARCH_MODEL" | "EXTERNAL_VLM";
  configurationState: "CONFIGURED" | "UNCONFIGURED" | "UNAVAILABLE";
  capabilities: string[];
  vision: boolean;
  streaming: boolean;
  toolCalling: boolean;
  structuredOutput: boolean;
  timeout: string | number;
  maxRetries: number;
  costMetadata: Record<string, string>;
}

export interface BatchItem {
  id: string;
  imageName: string;
  question: string;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
  answer: string | null;
  predictionOrigin: PredictionOrigin | null;
  confidence: number | null;
  latencyMs: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  attemptCount: number;
}

export interface BatchJob {
  id: string;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "COMPLETED_WITH_ERRORS" | "CANCELLED";
  totalItems: number;
  completedItems: number;
  failedItems: number;
  cancelRequested: boolean;
  modelReleaseId: string | null;
  progressPercent: number;
  items: BatchItem[];
  createdAt: string;
  updatedAt: string;
}

export interface AgentToolCall {
  id: string;
  name: string;
  status: string;
  inputSummary: string;
  output: string;
  latencyMs: number;
}

export interface AgentRun {
  runId: string;
  status: string;
  providerState: string;
  answer: string;
  traceId: string;
  latencyMs: number;
  toolCalls: AgentToolCall[];
  citations: Array<Record<string, string>>;
  boundaryNotice: string;
}

export interface KnowledgeDocument {
  id: string;
  title: string;
  sha256: string;
  mimeType: string;
  indexVersion: string;
  status: "INDEXING" | "READY" | "FAILED";
  errorMessage: string | null;
  createdAt: string;
}

export interface KnowledgeCitation {
  documentId: string;
  title: string;
  chunkIndex: number;
  content: string;
  score: number;
  indexVersion: string;
}

export interface KnowledgeSearchResult {
  requestId: string;
  query: string;
  citations: KnowledgeCitation[];
  latencyMs: number;
  embeddingModel: string;
  collection: string;
}

export interface SystemStatus {
  status: string;
  version: string;
  services: Record<string, { status: string; [key: string]: unknown }>;
}

export interface AuditEvent {
  id: string;
  eventType: string;
  entityType: string | null;
  entityId: string | null;
  traceId: string;
  outcome: string;
  summary: string | null;
  createdAt: string;
}
