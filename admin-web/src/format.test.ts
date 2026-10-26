import { describe, expect, it } from "vitest";
import { clock, duration, isActive, statusText } from "./format";

describe("statusText", () => {
  it("names every status in Chinese", () => {
    expect(statusText("queued")).toBe("排队中");
    expect(statusText("interrupted")).toBe("中断");
  });
});

describe("isActive", () => {
  it("is true only while a job can still change", () => {
    expect(isActive("queued")).toBe(true);
    expect(isActive("running")).toBe(true);
    expect(isActive("succeeded")).toBe(false);
    expect(isActive("cancelled")).toBe(false);
  });
});

describe("duration", () => {
  const start = "2026-01-01T09:00:00Z";
  it("shows a dash before the job starts", () => {
    expect(duration({})).toBe("—");
  });
  it("measures a finished job from its own times", () => {
    expect(duration({ startedAt: start, finishedAt: "2026-01-01T09:00:07Z" })).toBe("7 秒");
    expect(duration({ startedAt: start, finishedAt: "2026-01-01T09:02:05Z" })).toBe("2 分 5 秒");
    expect(duration({ startedAt: start, finishedAt: "2026-01-01T10:30:00Z" })).toBe("1 小时 30 分");
  });
  it("measures a running job up to now", () => {
    expect(duration({ startedAt: start }, Date.parse("2026-01-01T09:00:30Z"))).toBe("30 秒");
  });
});

describe("clock", () => {
  it("formats a time without throwing", () => {
    expect(clock("2026-01-01T09:00:00Z")).toContain("2026");
  });
});
