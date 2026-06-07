import { useState } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "../auth.jsx";
import { ErrorBox } from "../components/widgets.jsx";

export default function Login() {
  const { admin, login } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (admin) return <Navigate to="/" replace />;

  async function onSubmit(e) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await login(email.trim(), password);
      navigate("/");
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      <form className="card login-card" onSubmit={onSubmit}>
        <div className="brand brand-lg">
          <span className="brand-dot" />
          CalmSense <span className="brand-sub">Admin</span>
        </div>
        <p className="muted">Sign in to manage users and models.</p>
        <ErrorBox error={error} />
        <label>
          Email
          <input
            type="email"
            value={email}
            autoComplete="username"
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            autoComplete="current-password"
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        <button className="btn btn-primary" disabled={busy} type="submit">
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
