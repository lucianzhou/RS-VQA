import { afterEach, describe, expect, it, vi } from "vitest";
import { askConversation, listProjects } from "./api";

function abortableFetch(_input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  return new Promise((_resolve, reject) => {
    init?.signal?.addEventListener("abort", () => {
      reject(new DOMException("Aborted", "AbortError"));
    }, { once: true });
  });
}

describe("API request boundaries", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("distinguishes user cancellation from failure", async () => {
    vi.stubGlobal("fetch", vi.fn(abortableFetch));
    const controller = new AbortController();

    const request = askConversation("conversation-1", "图中有没有道路？", "research-rsvqa", undefined, controller.signal);
    const assertion = expect(request).rejects.toThrow("请求已取消");
    controller.abort();

    await assertion;
  });

  it("turns an expired request into an explicit timeout message", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", vi.fn(abortableFetch));

    const request = listProjects();
    const assertion = expect(request).rejects.toThrow("请求超时");
    await vi.advanceTimersByTimeAsync(30_000);

    await assertion;
  });
});
