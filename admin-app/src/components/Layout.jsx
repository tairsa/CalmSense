import { NavLink, useNavigate } from "react-router-dom";
import { useAuth } from "../auth.jsx";

export default function Layout({ children }) {
  const { admin, logout } = useAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate("/login");
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-dot" />
          CalmSense <span className="brand-sub">Admin</span>
          {/* Quiet build marker: the dashboard is the one surface where you
              can check which backend deploy you are actually looking at. */}
          <span className="brand-version">v{__APP_VERSION__}</span>
        </div>
        <nav className="topnav">
          <NavLink to="/" end>Dashboard</NavLink>
          <NavLink to="/users">Users</NavLink>
          <NavLink to="/admins">Admins</NavLink>
        </nav>
        <div className="topbar-right">
          <span className="muted">{admin?.name || admin?.email}</span>
          <button className="btn btn-ghost" onClick={handleLogout}>Log out</button>
        </div>
      </header>
      <main className="content">{children}</main>
    </div>
  );
}
