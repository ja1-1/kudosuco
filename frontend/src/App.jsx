import { Fragment, useEffect, useRef, useState } from "react";
import { button, Dialogo, el, empty, link, moderators } from "./componentes/ui.jsx";
import { audit } from "./modulos/Auditoria.jsx";
import { categories } from "./modulos/Categorias.jsx";
import { detail, feed } from "./modulos/Comunidad.jsx";
import { moderation } from "./modulos/Moderacion.jsx";
import { organization } from "./modulos/Organizacion.jsx";
import { profile } from "./modulos/Perfil.jsx";
import { Ingreso } from "./seguridad/Ingreso.jsx";
import { api } from "./servicios/cliente-http/clienteHttp.js";

function shell(view, page, user, options, announce, navigate) {
  const name = options.settings.platform_name;
  const main = el("main", { id: "principal", tabindex: "-1" }, view);
  const brand = link(name, user && user.status !== "ACTIVE" ? "#perfil" : "#inicio", "brand");
  if (!user) return el("div", { class: "layout" }, el("header", { class: "site-header" }, brand), main);
  const active = user.status === "ACTIVE";
  const pages = active ? [
    ["inicio", "Comunidad"], ["historial", "Mis reconocimientos"],
    ["organizacion", "Organización"], ["categorias", "Categorías"],
  ] : [];
  if (active && moderators.includes(user.role)) pages.push(["moderacion", "Moderación"]);
  if (active && ["ADMIN", ...moderators].includes(user.role)) pages.push(["auditoria", "Auditoría"]);
  pages.push(["perfil", "Mi perfil"]);
  const nav = el("nav", { "aria-label": "Principal" }, pages.map(([id, label]) => {
    const current = id === page || (page === "kudo" && id === "inicio");
    return el("a", { href: `#${id}`, "aria-current": current ? "page" : null }, label);
  }));
  const logout = button("Cerrar sesión", async () => {
    try {
      await api("/logout", "POST");
      announce("Sesión cerrada.");
      navigate("inicio");
    } catch (error) { announce(error.message); }
  }, "text");
  return el("div", { class: "layout" },
    el("header", { class: "site-header" }, brand, nav, logout),
    main);
}

export default function App() {
  const [view, setView] = useState(<main id="principal" tabIndex={-1} className="starting">Cargando UCO Kudos…</main>);
  const [notice, setNotice] = useState("");
  const [modal, setModal] = useState(null);
  const focusPending = useRef(false);
  const version = useRef(0);
  const timer = useRef();

  function announce(message) {
    setNotice(message);
    clearTimeout(timer.current);
    if (message) timer.current = setTimeout(() => setNotice(""), 4500);
  }

  function navigate(path) {
    if (location.hash === `#${path}`) render();
    else location.hash = path;
  }

  async function render(focus = false) {
    const current = ++version.current;
    const [path, search = ""] = location.hash.slice(1).split("?");
    let [page, id] = path.split("/");
    if (!page || page === "principal") page = "inicio";
    const query = new URLSearchParams(search);
    let result;
    try {
      const [options, user] = await Promise.all([
        api("/options"),
        api("/me").catch((error) => {
          if (error.status === 401) return null;
          throw error;
        }),
      ]);
      const ctx = {
        api, user, options, query, navigate, announce,
        dialog: (title, content) => setModal({ title, content }),
        refresh: (message) => { announce(message); return render(); },
      };
      if (user && user.status !== "ACTIVE") page = "perfil";
      const views = {
        inicio: () => feed(ctx), historial: () => feed(ctx, true),
        kudo: () => detail(ctx, id), perfil: () => profile(ctx),
        organizacion: () => organization(ctx), categorias: () => categories(ctx),
        moderacion: () => moderation(ctx), auditoria: () => audit(ctx),
      };
      let pageContent;
      try {
        pageContent = user ? await (views[page] || views.inicio)()
          : <Ingreso ctx={ctx} registering={page === "registro"} />;
      } catch (error) {
        pageContent = el("div", {}, empty("No se pudo abrir la página", error.message),
          link("Volver al inicio", "#inicio", "button secondary"));
      }
      result = shell(pageContent, page, user, options, announce, navigate);
    } catch (error) {
      result = el("main", { id: "principal", tabindex: "-1", class: "starting" },
        empty("No hay conexión con UCO Kudos", error.message),
        button("Reintentar", () => render(), "primary"));
    }
    if (current === version.current) {
      focusPending.current = focus;
      setView(<Fragment key={current}>{result}</Fragment>);
    }
  }

  useEffect(() => {
    const change = () => { setModal(null); render(true); };
    window.addEventListener("hashchange", change);
    render();
    return () => {
      version.current++;
      clearTimeout(timer.current);
      window.removeEventListener("hashchange", change);
    };
  }, []);

  useEffect(() => {
    if (focusPending.current) document.getElementById("principal")?.focus();
  }, [view]);

  return <>
    <a className="skip" href="#principal" onClick={(event) => {
      event.preventDefault();
      document.getElementById("principal")?.focus();
    }}>Ir al contenido</a>
    {view}
    <div className="toast" role="status" aria-live="polite">{notice}</div>
    {modal && <Dialogo {...modal} close={() => setModal(null)} />}
  </>;
}
