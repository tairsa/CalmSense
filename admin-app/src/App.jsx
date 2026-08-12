import { Navigate, Route, Routes } from "react-router-dom";
import Layout from "./components/Layout.jsx";
import { useAuth } from "./auth.jsx";
import Login from "./pages/Login.jsx";
import Dashboard from "./pages/Dashboard.jsx";
import Users from "./pages/Users.jsx";
import UserDetail from "./pages/UserDetail.jsx";
import Accounts from "./pages/Accounts.jsx";
import Admins from "./pages/Admins.jsx";

function Protected({ children }) {
  const { admin, loading } = useAuth();
  if (loading) return <div className="centered muted">Loading…</div>;
  if (!admin) return <Navigate to="/login" replace />;
  return <Layout>{children}</Layout>;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/" element={<Protected><Dashboard /></Protected>} />
      <Route path="/users" element={<Protected><Users /></Protected>} />
      <Route path="/users/:userId" element={<Protected><UserDetail /></Protected>} />
      <Route path="/accounts" element={<Protected><Accounts /></Protected>} />
      <Route path="/admins" element={<Protected><Admins /></Protected>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
