import React from "react";
import ReactDOM from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import "./index.css";
import App from "./App";
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

const router = createBrowserRouter([
  {
    path: "/",
    element: <App />,
    children: [
      { index: true, element: <Dashboard /> },
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
    ],
  },
]);

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <RouterProvider router={router} />
  </React.StrictMode>
);
