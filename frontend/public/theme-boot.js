// Light unless the visitor chose dark; applied before first paint. A file
// rather than an inline script so the Content-Security-Policy can allow
// scripts from this origin only.
try {
  if (localStorage.getItem("continuum.theme.choice") !== "dark") {
    document.documentElement.setAttribute("data-theme", "light");
    document.documentElement.style.colorScheme = "light";
  }
} catch (e) {
  document.documentElement.setAttribute("data-theme", "light");
}
