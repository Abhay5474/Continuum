import React from "react";
import ReactDOM from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import "./index.css";
import App from "./App";
import Landing from "./pages/Landing";
import Docs from "./pages/Docs";
import Dashboard from "./pages/Dashboard";
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
import { ToastProvider } from "./components/ui";

const router = createBrowserRouter([
  // Standalone marketing + docs (no app chrome).
  { path: "/", element: <Landing /> },
  { path: "/docs", element: <Docs /> },
  // The app shell wraps every existing feature route (paths unchanged); the
  // dashboard now lives at /dashboard.
  {
    element: <App />,
    children: [
      { path: "dashboard", element: <Dashboard /> },
      { path: "workflows/:id", element: <WorkflowDetailPage /> },
      { path: "chaos", element: <ChaosPanel /> },
      { path: "replay", element: <ReplayVerify /> },
      { path: "router", element: <ModelRouter /> },
      { path: "ai-chaos", element: <AiChaosLab /> },
      { path: "memory", element: <Memory /> },
      { path: "gateway", element: <GatewayDashboard /> },
      { path: "portal", element: <DeveloperPortal /> },
      { path: "autopilot", element: <Autopilot /> },
      { path: "godmode", element: <GodMode /> },
      { path: "dag", element: <DagCommandCenter /> },
      { path: "dag/:workflowId", element: <DagCommandCenter /> },
      { path: "mmu", element: <MmuProfiler /> },
    ],
  },
]);

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ToastProvider>
      <RouterProvider router={router} />
    </ToastProvider>
  </React.StrictMode>
);
