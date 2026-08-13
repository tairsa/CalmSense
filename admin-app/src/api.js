// Thin fetch wrapper for the CalmSense admin API.
// In dev, Vite proxies /api -> the FastAPI backend (see vite.config.js).
// Set VITE_API_BASE to point at an absolute backend URL in other setups.

const BASE = import.meta.env.VITE_API_BASE || "";
const TOKEN_KEY = "calmsense_admin_token";

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}
export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

export class ApiError extends Error {
  constructor(status, detail) {
    super(detail || `Request failed (${status})`);
    this.status = status;
    this.detail = detail;
  }
}

async function request(path, { method = "GET", body, auth = true } = {}) {
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (auth) {
    const token = getToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (res.status === 401 && auth) {
    setToken(null);
    // Let the app's route guard redirect to /login on next render.
    window.dispatchEvent(new Event("calmsense-unauthorized"));
  }

  let data = null;
  const text = await res.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!res.ok) {
    const detail = data && data.detail ? data.detail : `Request failed (${res.status})`;
    throw new ApiError(res.status, detail);
  }
  return data;
}

export const api = {
  login: (email, password) =>
    request("/api/v1/admin/auth/login", { method: "POST", body: { email, password }, auth: false }),
  me: () => request("/api/v1/admin/auth/me"),
  register: (payload) =>
    request("/api/v1/admin/auth/register", { method: "POST", body: payload }),
  admins: () => request("/api/v1/admin/admins"),

  globalMetrics: () => request("/api/v1/admin/metrics/global"),
  users: () => request("/api/v1/admin/users"),
  userDetail: (id) => request(`/api/v1/admin/users/${encodeURIComponent(id)}`),
  userReports: (id) => request(`/api/v1/admin/users/${encodeURIComponent(id)}/reports`),
  userFeedback: (id) => request(`/api/v1/admin/users/${encodeURIComponent(id)}/feedback`),
  userSensor: (id) => request(`/api/v1/admin/users/${encodeURIComponent(id)}/sensor-data`),

  modelHistory: (id) => request(`/api/v1/admin/users/${encodeURIComponent(id)}/model/history`),
  retrain: (id, payload) =>
    request(`/api/v1/admin/users/${encodeURIComponent(id)}/model/retrain`, { method: "POST", body: payload || {} }),
  rollback: (id, cutoff) =>
    request(`/api/v1/admin/users/${encodeURIComponent(id)}/model/rollback`, { method: "POST", body: { cutoff } }),
  reset: (id) =>
    request(`/api/v1/admin/users/${encodeURIComponent(id)}/model/reset`, { method: "POST" }),
};
