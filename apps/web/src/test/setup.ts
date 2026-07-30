import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, vi } from "vitest";

afterEach(() => cleanup());
beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-csrf-token; Path=/";
});

// Radix dispatches focus-scope events from a queued callback. Node 22 also
// exposes its own Event implementation, which jsdom correctly rejects for DOM
// dispatch. Keep tests on the same event realm as the rendered document.
Object.defineProperty(globalThis, "Event", {
  configurable: true,
  value: window.Event,
});
Object.defineProperty(globalThis, "CustomEvent", {
  configurable: true,
  value: window.CustomEvent,
});

const storage = new Map<string, string>();
const localStorageStub: Storage = {
  get length() {
    return storage.size;
  },
  clear() {
    storage.clear();
  },
  getItem(key) {
    return storage.get(key) ?? null;
  },
  key(index) {
    return [...storage.keys()][index] ?? null;
  },
  removeItem(key) {
    storage.delete(key);
  },
  setItem(key, value) {
    storage.set(key, String(value));
  },
};

Object.defineProperty(globalThis, "localStorage", {
  configurable: true,
  value: localStorageStub,
});

Object.defineProperty(URL, "createObjectURL", {
  value: vi.fn(() => "blob:test-image"),
  writable: true,
});
Object.defineProperty(URL, "revokeObjectURL", {
  value: vi.fn(),
  writable: true,
});

Object.defineProperty(Element.prototype, "hasPointerCapture", {
  value: vi.fn(() => false),
});
Object.defineProperty(Element.prototype, "setPointerCapture", {
  value: vi.fn(),
});
Object.defineProperty(Element.prototype, "releasePointerCapture", {
  value: vi.fn(),
});

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

vi.stubGlobal("ResizeObserver", ResizeObserverStub);
