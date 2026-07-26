import * as Popover from "@radix-ui/react-popover";
import { useQuery } from "@tanstack/react-query";
import { Bot, Check, ChevronDown, ShieldCheck, Sparkles } from "lucide-react";
import { useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { listProviders } from "../api";
import { useWorkspaceStore } from "../store";
import type { ModelOption, ProviderDescriptor } from "../types";

export function AppTopbar({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <header className="topbar">
      <div className="topbar-title"><h1>{title}</h1>{subtitle && <span>{subtitle}</span>}</div>
      <div className="topbar-actions">{actions}</div>
    </header>
  );
}

export const modelOptions: ModelOption[] = [
  {
    id: "research-rsvqa",
    name: "标准化 RS-VQA",
    description: "qdrop15 · predicted-soft · 当前为 Mock",
    kind: "MOCK",
    configured: true,
    releaseId: "mock-demo-not-a-research-release",
  },
  {
    id: "external-vlm",
    name: "Gemini-3.6-flash",
    description: "Google Gemini · 多模态视觉问答",
    kind: "EXTERNAL_VLM",
    configured: false,
  },
  {
    id: "qwen",
    name: "Qwen3-VL 32B",
    description: "Qwen · 多模态视觉问答",
    kind: "EXTERNAL_VLM",
    configured: false,
  },
];

export function ModelSelector() {
  const [open, setOpen] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const providers = useQuery({ queryKey: ["providers"], queryFn: listProviders, staleTime: 10_000 });
  const discoveredOptions = Array.isArray(providers.data) ? providers.data.map(providerToModelOption) : [];
  const options = discoveredOptions.length > 0 ? discoveredOptions : modelOptions;
  const selectedModelId = useWorkspaceStore((state) => state.selectedModelId);
  const setSelectedModelId = useWorkspaceStore((state) => state.setSelectedModelId);
  const selected = options.find((model) => model.id === selectedModelId) ?? options[0];

  const navigateOptions = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const candidates = Array.from(listRef.current?.querySelectorAll<HTMLButtonElement>("[role=option]:not([aria-disabled=true])") ?? []);
    if (candidates.length === 0) return;
    const current = candidates.indexOf(document.activeElement as HTMLButtonElement);
    const index = event.key === "Home"
      ? 0
      : event.key === "End"
        ? candidates.length - 1
        : event.key === "ArrowDown"
          ? (current + 1 + candidates.length) % candidates.length
          : (current - 1 + candidates.length) % candidates.length;
    candidates[index]?.focus();
  };

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button className="model-selector" type="button" aria-label={`选择分析模式，当前为${selected.name}`}>
          <span className="model-option-icon"><ProviderAvatar providerId={selected.id} kind={selected.kind} size={20} /></span>
          <span className="model-selector-info">
            <strong>{selected.name}</strong>
            <small>{selected.configured ? selected.description : "未配置"}</small>
          </span>
          <ChevronDown className={open ? "is-open" : ""} size={14} />
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content className="model-popover" sideOffset={8} align="end" collisionPadding={12} onOpenAutoFocus={(event) => {
          event.preventDefault();
          window.requestAnimationFrame(() => listRef.current?.querySelector<HTMLButtonElement>("[role=option]:not([aria-disabled=true])")?.focus());
        }}>
          <div className="popover-heading">
            <span>分析模式</span>
            <small>不同模式的输出来源严格分离</small>
          </div>
          <div ref={listRef} className="model-options" role="listbox" aria-label="分析模式" onKeyDown={navigateOptions}>
            {options.map((model) => {
              const isSelected = model.id === selected.id;
              return (
                <button
                  className={`model-option ${isSelected ? "is-selected" : ""}`}
                  key={model.id}
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  aria-disabled={!model.configured}
                  onClick={() => {
                    if (!model.configured) return;
                    setSelectedModelId(model.id);
                    setOpen(false);
                  }}
                >
                  <span className="model-option-icon"><ProviderAvatar providerId={model.id} kind={model.kind} size={20} /></span>
                  <span className="model-option-copy">
                    <strong>{model.name}</strong>
                    <small>{model.description}</small>
                    <em>{model.configured ? model.releaseId ?? "Provider 已配置" : "需要服务端 API 配置"}</em>
                  </span>
                  {isSelected && <Check size={16} aria-hidden="true" />}
                </button>
              );
            })}
          </div>
          <div className="model-boundary-note"><ShieldCheck size={13} /><span>Gemini 与 Qwen3-VL 的回答不会覆盖 RS-VQA 研究模型结果。</span></div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

export function ProviderAvatar({ providerId, kind, size = 28 }: { providerId?: string; kind: string; size?: number }) {
  const isExternal = kind === "EXTERNAL_VLM";
  if (!isExternal) {
    return <span className="avatar-icon research" style={{ width: size, height: size }}><Bot size={Math.round(size * 0.57)} aria-hidden="true" /></span>;
  }
  if (providerId === "qwen") {
    return <span className="avatar-icon external qwen" style={{ width: size, height: size }}>Q</span>;
  }
  if (providerId === "gemini") {
    return <span className="avatar-icon external gemini" style={{ width: size, height: size }}>G</span>;
  }
  return <span className="avatar-icon external" style={{ width: size, height: size }}>E</span>;
}

export function providerToModelOption(provider: ProviderDescriptor): ModelOption {
  const isMock = provider.kind === "RESEARCH_MODEL" && provider.modelId.startsWith("mock-");
  return {
    id: provider.providerId,
    name: isMock
      ? "标准化 RS-VQA"
      : provider.providerId === "gemini"
        ? geminiModelName(provider.modelId)
        : provider.providerId === "qwen"
          ? "Qwen3-VL 32B"
          : provider.displayName,
    description: isMock
      ? "qdrop15 · predicted-soft · 当前为 Mock"
      : provider.kind === "RESEARCH_MODEL"
        ? "RSVQA-HR · qdrop15 · predicted-soft"
        : provider.providerId === "gemini"
          ? "Google Gemini · 多模态视觉问答"
          : provider.providerId === "qwen"
            ? "Qwen · 多模态视觉问答"
            : provider.modelId,
    kind: isMock ? "MOCK" : provider.kind,
    configured: provider.configurationState === "CONFIGURED",
    releaseId: provider.modelId === "none" ? undefined : provider.modelId,
  };
}

function geminiModelName(modelId: string) {
  if (!modelId || modelId === "none" || modelId === "未配置") return "Gemini";
  return modelId.startsWith("gemini-") ? `Gemini-${modelId.slice("gemini-".length)}` : modelId;
}

export function StatusBadge({ children, tone = "neutral" }: { children: ReactNode; tone?: "neutral" | "success" | "warning" }) {
  return <span className={`status-badge ${tone}`}><Sparkles size={12} aria-hidden="true" />{children}</span>;
}
