import React from "react";
import ReactDOM from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import "./index.css";
import App from "./App";
import RequireAuth from "./components/RequireAuth";
import Landing from "./pages/Landing";
import SignIn from "./pages/SignIn";
import { ToastProvider } from "./components/ui";
import { NotFound, RouteError } from "./system/RouteError";
import { OperatorProvider } from "./system/OperatorAccess";
import { installMotionTokens, installRipple, installSurfaceLight, installTooltips } from "./system/physics";

// Before the first render, so the first frame already speaks the motion
// language: every --ease-* and --dur-* the stylesheet reads comes from here.
installMotionTokens();
installSurfaceLight();
installTooltips();
installRipple();

/**
 * A route whose page is downloaded when first visited. The public landing page
 * used to carry every console page with it — one 840 KB script before anything
 * drew. The router resolves the import before it commits the navigation, so a
 * page never flashes a spinner in place of the one being left.
 */
const page = (load: () => Promise<{ default: React.ComponentType }>) => async () => ({
  Component: (await load()).default,
});

const router = createBrowserRouter([
  // Public: marketing, docs and authentication.
  { path: "/", element: <Landing /> },
  { path: "/docs", lazy: page(() => import("./pages/Docs")) },
  { path: "/signin", element: <SignIn /> },
  // Open by necessity: an invitee has no account until they accept.
  { path: "/accept-invite", lazy: page(() => import("./pages/AcceptInvite")) },

  // Console. Every route below requires a session — the APIs behind them are
  // tenant-scoped, so an anonymous visitor has nothing legitimate to render.
  // Paths are unchanged so existing links keep working.
  {
    element: <RequireAuth />,
    errorElement: <RouteError />,
    children: [
      {
        element: <App />,
        children: [
          {
            // Pathless: a page that fails renders its error inside the console,
            // with the bar still there, rather than replacing the whole app.
            errorElement: <RouteError />,
            children: [
          // The command centre is the front of the console; the workflow
          // console it replaced stays reachable at /workflows.
          { path: "dashboard", lazy: page(() => import("./pages/CommandCenter")) },
          // /workflows is now where a developer authors and runs their own
          // definitions; the original run console keeps its own path. A static
          // segment outranks :id, so /workflows/console is unambiguous.
          { path: "workflows", lazy: page(() => import("./pages/WorkflowBuilder")) },
          { path: "workflows/console", lazy: page(() => import("./pages/Dashboard")) },
          { path: "workflows/:id", lazy: page(() => import("./pages/WorkflowDetail")) },
          { path: "chaos", lazy: page(() => import("./pages/ChaosPanel")) },
          { path: "replay", lazy: page(() => import("./pages/ReplayVerify")) },
          { path: "router", lazy: page(() => import("./pages/ModelRouter")) },
          { path: "ai-chaos", lazy: page(() => import("./pages/AiChaosLab")) },
          { path: "memory", lazy: page(() => import("./pages/Memory")) },
          { path: "gateway", lazy: page(() => import("./pages/GatewayDashboard")) },
          { path: "portal", lazy: page(() => import("./pages/DeveloperPortal")) },
          { path: "billing", lazy: page(() => import("./pages/Billing")) },
          { path: "settings", lazy: page(() => import("./pages/Settings")) },
          { path: "autopilot", lazy: page(() => import("./pages/Autopilot")) },
          { path: "godmode", lazy: page(() => import("./pages/GodMode")) },
          { path: "dag", lazy: page(() => import("./pages/DagCommandCenter")) },
          { path: "dag/:workflowId", lazy: page(() => import("./pages/DagCommandCenter")) },
          { path: "mmu", lazy: page(() => import("./pages/MmuProfiler")) },
          { path: "guard", lazy: page(() => import("./pages/PromptGuard")) },
          { path: "cache", lazy: page(() => import("./pages/SemanticCache")) },
          { path: "cascade", lazy: page(() => import("./pages/Cascade")) },
          { path: "confidence", lazy: page(() => import("./pages/Uncertainty")) },
          { path: "quality", lazy: page(() => import("./pages/QualityGate")) },
          { path: "breaker", lazy: page(() => import("./pages/Breaker")) },
          { path: "specialists", lazy: page(() => import("./pages/Specialists")) },
          { path: "pipelines", lazy: page(() => import("./pages/Pipelines")) },
          { path: "admission", lazy: page(() => import("./pages/Admission")) },
          { path: "scheduling", lazy: page(() => import("./pages/Scheduling")) },
          { path: "cost-limits", lazy: page(() => import("./pages/CostAdmission")) },
          { path: "compression", lazy: page(() => import("./pages/CompressionPolicy")) },
          { path: "context", lazy: page(() => import("./pages/ContextTransformers")) },
          { path: "counterfactual", lazy: page(() => import("./pages/Counterfactual")) },
          { path: "loops", lazy: page(() => import("./pages/LoopGuard")) },
          { path: "saga", lazy: page(() => import("./pages/Saga")) },
          { path: "provenance", lazy: page(() => import("./pages/Provenance")) },
          { path: "accounts", lazy: page(() => import("./pages/Accounts")) },
          { path: "models", lazy: page(() => import("./pages/Models")) },
          { path: "*", element: <NotFound /> },
            ],
          },
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
