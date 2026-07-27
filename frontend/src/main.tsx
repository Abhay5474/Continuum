import React from "react";
import ReactDOM from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import "./index.css";
import App from "./App";
import RequireAuth from "./components/RequireAuth";
import Landing from "./pages/Landing";
import Docs from "./pages/Docs";
import SignIn from "./pages/SignIn";
import AcceptInvite from "./pages/AcceptInvite";
import Dashboard from "./pages/Dashboard";
import CommandCenter from "./pages/CommandCenter";
import WorkflowBuilder from "./pages/WorkflowBuilder";
import WorkflowDetailPage from "./pages/WorkflowDetail";
import ChaosPanel from "./pages/ChaosPanel";
import ReplayVerify from "./pages/ReplayVerify";
import ModelRouter from "./pages/ModelRouter";
import AiChaosLab from "./pages/AiChaosLab";
import Memory from "./pages/Memory";
import GatewayDashboard from "./pages/GatewayDashboard";
import DeveloperPortal from "./pages/DeveloperPortal";
import Autopilot from "./pages/Autopilot";
import GodMode from "./pages/GodMode";
import DagCommandCenter from "./pages/DagCommandCenter";
import MmuProfiler from "./pages/MmuProfiler";
import Billing from "./pages/Billing";
import Settings from "./pages/Settings";
import PromptGuard from "./pages/PromptGuard";
import SemanticCache from "./pages/SemanticCache";
import Cascade from "./pages/Cascade";
import Uncertainty from "./pages/Uncertainty";
import QualityGatePage from "./pages/QualityGate";
import BreakerPage from "./pages/Breaker";
import Specialists from "./pages/Specialists";
import { ToastProvider } from "./components/ui";
import { OperatorProvider } from "./system/OperatorAccess";

const router = createBrowserRouter([
  // Public: marketing, docs and authentication.
  { path: "/", element: <Landing /> },
  { path: "/docs", element: <Docs /> },
  { path: "/signin", element: <SignIn /> },
  // Open by necessity: an invitee has no account until they accept.
  { path: "/accept-invite", element: <AcceptInvite /> },

  // Console. Every route below requires a session — the APIs behind them are
  // tenant-scoped, so an anonymous visitor has nothing legitimate to render.
  // Paths are unchanged so existing links keep working.
  {
    element: <RequireAuth />,
    children: [
      {
        element: <App />,
        children: [
          // The command centre is the front of the console; the workflow
          // console it replaced stays reachable at /workflows.
          { path: "dashboard", element: <CommandCenter /> },
          // /workflows is now where a developer authors and runs their own
          // definitions; the original run console keeps its own path. A static
          // segment outranks :id, so /workflows/console is unambiguous.
          { path: "workflows", element: <WorkflowBuilder /> },
          { path: "workflows/console", element: <Dashboard /> },
          { path: "workflows/:id", element: <WorkflowDetailPage /> },
          { path: "chaos", element: <ChaosPanel /> },
          { path: "replay", element: <ReplayVerify /> },
          { path: "router", element: <ModelRouter /> },
          { path: "ai-chaos", element: <AiChaosLab /> },
          { path: "memory", element: <Memory /> },
          { path: "gateway", element: <GatewayDashboard /> },
          { path: "portal", element: <DeveloperPortal /> },
          { path: "billing", element: <Billing /> },
          { path: "settings", element: <Settings /> },
          { path: "autopilot", element: <Autopilot /> },
          { path: "godmode", element: <GodMode /> },
          { path: "dag", element: <DagCommandCenter /> },
          { path: "dag/:workflowId", element: <DagCommandCenter /> },
          { path: "mmu", element: <MmuProfiler /> },
          { path: "guard", element: <PromptGuard /> },
          { path: "cache", element: <SemanticCache /> },
          { path: "cascade", element: <Cascade /> },
          { path: "confidence", element: <Uncertainty /> },
          { path: "quality", element: <QualityGatePage /> },
          { path: "breaker", element: <BreakerPage /> },
          { path: "specialists", element: <Specialists /> },
        ],
      },
    ],
  },
]);

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ToastProvider>
      <OperatorProvider>
        <RouterProvider router={router} />
      </OperatorProvider>
    </ToastProvider>
  </React.StrictMode>
);
