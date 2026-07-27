import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { AgentHistoryRun } from "../types";
import { RsBotChat } from "./RsBotChat";

function run(overrides: Partial<AgentHistoryRun> = {}): AgentHistoryRun {
  return {
    runId: "run-1",
    status: "COMPLETED",
    input: "汇总这个项目的 VQA 结果",
    answer: "项目共 42 个问题，其中 2 个明确复核项。",
    traceId: "trace-1",
    latencyMs: 820,
    providerId: "gemini",
    providerModel: "gemini-3.6-flash",
    totalTokens: 480,
    toolCalls: [],
    createdAt: new Date().toISOString(),
    providerState: "LLM_PLANNING",
    promptVersion: "rs-bot/1.0.0",
    stopReason: "completed",
    toolSteps: 3,
    ...overrides,
  };
}

function renderChat(props: Partial<Parameters<typeof RsBotChat>[0]> = {}) {
  const onAsk = vi.fn();
  const onCancel = vi.fn();
  render(
    <RsBotChat
      runs={[]}
      isRunning={false}
      stage=""
      pendingQuestion=""
      placeholder="向 RS-Bot 提问…"
      onAsk={onAsk}
      onCancel={onCancel}
      {...props}
    />,
  );
  return { onAsk, onCancel };
}

describe("RsBotChat", () => {
  it("renders a turn with its question, answer and provenance", () => {
    renderChat({ runs: [run()] });

    expect(screen.getByText("汇总这个项目的 VQA 结果")).toBeInTheDocument();
    expect(screen.getByText("项目共 42 个问题，其中 2 个明确复核项。")).toBeInTheDocument();
    expect(screen.getByText(/trace-1/)).toBeInTheDocument();
    expect(screen.getByText(/gemini-3.6-flash/)).toBeInTheDocument();
    expect(screen.getByText("3 步")).toBeInTheDocument();
  });

  it("renders agent Markdown as safe semantic content", () => {
    const { container } = render(
      <RsBotChat
        runs={[run({
          answer: [
            "### 模型状态",
            "",
            "**发布版本** `rsvqa-release-1234567890`",
            "",
            "- 服务正常",
            "- 支持批量任务",
            "",
            "[查看说明](https://example.com/docs)",
            "",
            "---",
            "",
            "<script>window.bad = true</script>",
          ].join("\n"),
        })]}
        isRunning={false}
        stage=""
        pendingQuestion=""
        placeholder="p"
        onAsk={vi.fn()}
        onCancel={vi.fn()}
      />,
    );

    expect(screen.getByRole("heading", { level: 3, name: "模型状态" })).toBeInTheDocument();
    expect(screen.getByText("发布版本").tagName).toBe("STRONG");
    expect(screen.getByText("rsvqa-release-1234567890").tagName).toBe("CODE");
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByRole("separator")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "查看说明" })).toHaveAttribute("href", "https://example.com/docs");
    expect(container.textContent).not.toContain("###");
    expect(container.textContent).not.toContain("**");
    expect(container.querySelector("script")).toBeNull();
  });

  it("removes unsafe Markdown link protocols", () => {
    renderChat({ runs: [run({ answer: "[危险链接](javascript:alert('x'))" })] });

    expect(screen.getByText("危险链接")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "危险链接" })).toBeNull();
  });

  it("shows the multi-tool trace including rejected calls", () => {
    renderChat({
      runs: [run({
        toolCalls: [
          { id: "t1", name: "project_summary", status: "COMPLETED", inputSummary: "{}", output: "{}", latencyMs: 12 },
          { id: "t2", name: "confidence_distribution", status: "COMPLETED", inputSummary: "{}", output: "{}", latencyMs: 9 },
          { id: "t3", name: "delete_everything", status: "REJECTED", inputSummary: "{}", output: "不在白名单", latencyMs: 0 },
        ],
      })],
    });

    expect(screen.getByText("项目摘要")).toBeInTheDocument();
    expect(screen.getByText("置信度分布")).toBeInTheDocument();
    expect(screen.getByText(/已拒绝/)).toBeInTheDocument();
  });

  it("states plainly when planning is off instead of showing a status token", () => {
    renderChat({ runs: [run({ providerState: "RULE_BASED_TOOLS", stopReason: "rule_based_single_tool" })] });

    expect(screen.getByText("RS-Bot 当前处于规则工具模式，未启用智能规划")).toBeInTheDocument();
    expect(screen.queryByText(/UNCONFIGURED_RULE_BASED_TOOL_ORCHESTRATION/)).toBeNull();
  });

  it("explains why a run stopped early", () => {
    renderChat({ runs: [run({ stopReason: "max_steps_reached" })] });

    expect(screen.getByText("已达到最大工具步数")).toBeInTheDocument();
  });

  it("sends the composed question and clears the box", async () => {
    const user = userEvent.setup();
    const { onAsk } = renderChat();

    await user.type(screen.getByLabelText("向 RS-Bot 提问"), "查询当前模型版本");
    await user.click(screen.getByRole("button", { name: "发送给 RS-Bot" }));

    expect(onAsk).toHaveBeenCalledWith("查询当前模型版本");
    expect(screen.getByLabelText("向 RS-Bot 提问")).toHaveValue("");
  });

  it("offers a cancel control while a run is in flight", async () => {
    const user = userEvent.setup();
    const { onCancel } = renderChat({ isRunning: true, stage: "tool_started", pendingQuestion: "汇总" });

    expect(screen.getByText("正在执行工具")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "发送给 RS-Bot" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "停止 RS-Bot" }));

    expect(onCancel).toHaveBeenCalled();
  });

  it("shows starter suggestions only before the first turn", () => {
    const { rerender } = render(
      <RsBotChat
        runs={[]}
        isRunning={false}
        stage=""
        pendingQuestion=""
        placeholder="p"
        suggestions={["查询当前模型版本"]}
        onAsk={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: "查询当前模型版本" })).toBeInTheDocument();

    rerender(
      <RsBotChat
        runs={[run()]}
        isRunning={false}
        stage=""
        pendingQuestion=""
        placeholder="p"
        suggestions={["查询当前模型版本"]}
        onAsk={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.queryByRole("button", { name: "查询当前模型版本" })).toBeNull();
  });

  it("surfaces run errors to assistive technology", () => {
    renderChat({ error: "Agent 调用失败。" });

    expect(screen.getByRole("alert")).toHaveTextContent("Agent 调用失败。");
  });
});
