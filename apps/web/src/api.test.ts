import { afterEach, describe, expect, it, vi } from "vitest";
import { askConversation, createProject, listProjects } from "./api";

function abortableFetch(_input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  return new Promise((_resolve, reject) => {
    init?.signal?.addEventListener("abort", () => {
      reject(new DOMException("Aborted", "AbortError"));
    }, { once: true });
  });
}

describe("API request boundaries", () => {
  afterEach(() => {
    document.cookie = "XSRF-TOKEN=; Max-Age=0; Path=/";
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

  it("obtains a CSRF token and attaches it to unsafe requests", async () => {
    document.cookie = "XSRF-TOKEN=raw-cookie-token; Path=/";
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        token: "masked-response-token",
        headerName: "X-XSRF-TOKEN",
      }), { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: "project-1",
        name: "CSRF project",
        archived: false,
        conversations: [],
      }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await createProject("CSRF project");

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "/api/v1/auth/csrf",
      expect.objectContaining({ credentials: "same-origin" }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "/api/v1/projects",
      expect.objectContaining({
        headers: expect.objectContaining({ "X-XSRF-TOKEN": "raw-cookie-token" }),
      }),
    );
  });
});
