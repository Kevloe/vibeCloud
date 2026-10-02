import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { ToastProvider } from "./ui/Toast";
import "./index.css";

// Der ToastProvider liegt ueber der ganzen Anwendung: Auch die Anmeldung und das
// Abmelden melden sich darueber, nicht nur die Seiten dahinter.
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ToastProvider>
      <App />
    </ToastProvider>
  </StrictMode>,
);
