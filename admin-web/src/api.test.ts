import { describe, expect, it, vi, afterEach } from "vitest";
import { ApiError, call, loginMessage, waitForJob } from "./api";

afterEach(() => vi.unstubAllGlobals());

function reply(status: number, body: unknown) {
  return vi.fn().mockResolvedValue({ ok: status < 400, status, text: async () => JSON.stringify(body) });
}

describe("call", () => {
  it("sends our header and the json body", async () => {
    const fetchMock = reply(200, { user: "owner" });
    vi.stubGlobal("fetch", fetchMock);
    await call("POST", "/api/auth/login", { a: 1 });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/auth/login");
    expect(init.headers["X-Requested-With"]).toBe("akali-admin");
    expect(init.body).toBe('{"a":1}');
  });

  it("turns an error reply into an ApiError carrying the status", async () => {
    vi.stubGlobal("fetch", reply(401, { error: "not logged in" }));
    await expect(call("GET", "/api/auth/me")).rejects.toMatchObject({ status: 401, message: "not logged in" });
  });
});

describe("loginMessage", () => {
  it("explains each failure in Chinese", () => {
    expect(loginMessage(new ApiError(401, "x"))).toBe("用户名或密码不对");
    expect(loginMessage(new ApiError(429, "x"))).toContain("太多");
    expect(loginMessage(new Error("network"))).toContain("失败");
  });
});

describe("waitForJob", () => {
  it("keeps asking until the job has finished and its last log lines are read", async () => {
    const job = (status: string) => ({ id: 5, status });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, status: 200, text: async () => JSON.stringify({ job: job("running"), logs: [{ id: 1, line: "a" }] }) })
      .mockResolvedValueOnce({ ok: true, status: 200, text: async () => JSON.stringify({ job: job("failed"), logs: [{ id: 2, line: "boom" }] }) })
      .mockResolvedValueOnce({ ok: true, status: 200, text: async () => JSON.stringify({ job: job("failed"), logs: [] }) });
    vi.stubGlobal("fetch", fetchMock);
    const r = await waitForJob(5, 0);
    expect(r.job.status).toBe("failed");
    expect(r.tail).toEqual(["a", "boom"]);
    expect(fetchMock.mock.calls[1][0]).toBe("/api/jobs/5?after=1");
  });
});
