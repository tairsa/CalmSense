import { createContext, useContext, useEffect, useState } from "react";
import { api, getToken, setToken } from "./api.js";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [admin, setAdmin] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    async function bootstrap() {
      if (!getToken()) {
        setLoading(false);
        return;
      }
      try {
        const me = await api.me();
        if (active) setAdmin(me);
      } catch {
        setToken(null);
      } finally {
        if (active) setLoading(false);
      }
    }
    bootstrap();

    const onUnauthorized = () => setAdmin(null);
    window.addEventListener("calmsense-unauthorized", onUnauthorized);
    return () => {
      active = false;
      window.removeEventListener("calmsense-unauthorized", onUnauthorized);
    };
  }, []);

  async function login(email, password) {
    const res = await api.login(email, password);
    setToken(res.access_token);
    setAdmin(res.admin);
    return res.admin;
  }

  function logout() {
    setToken(null);
    setAdmin(null);
  }

  return (
    <AuthContext.Provider value={{ admin, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
