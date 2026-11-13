import { useEffect, useRef, useState } from "react";
import { button, chip, date, el, empty, field, form, link, moderators, reasons, section, select, table } from "../componentes/ui.jsx";
import { blank, withBlank, page, panel, required, listUrl, pager } from "./presentacion.js";

function ratingLabel(item) {
  return item.rating_count ? `★ ${item.average} · ${item.rating_count} valoraciones` : "☆ Sin valoraciones";
}

function kudoCard(item) {
  return el("article", { class: "kudo-card" },
    el("div", { class: "card-meta" }, chip(item.category_name, "green"), el("time", { datetime: item.created_at }, date(item.created_at))),
    el("p", {}, link(item.author, `#inicio?sent=${item.author_id}`), " reconoció a ", link(item.recipient, `#inicio?received=${item.recipient_id}`)),
    el("p", { class: "body-copy preview" }, item.body),
    el("div", { class: "card-footer" }, el("span", { class: "stars" }, ratingLabel(item)),
      link("Abrir conversación", `#kudo/${item.id}`)));
}

function PublicarKudo({ ctx, available, close }) {
  const [candidates, setCandidates] = useState([]);
  const [message, setMessage] = useState("Busca por nombre si la persona no aparece.");
  const search = useRef(null);
  const version = useRef(0);

  async function find() {
    const latest = ++version.current;
    try {
      const query = new URLSearchParams({ q: search.current.querySelector("input").value });
      const people = await ctx.api(listUrl("/people", query));
      if (latest !== version.current) return;
      const eligible = people.items.filter((item) => item.id !== ctx.user.id);
      setCandidates(eligible);
      setMessage(people.next ? "Hay más resultados. Acota la búsqueda." : `${eligible.length} coincidencias.`);
    } catch (error) {
      if (latest === version.current) setMessage(error.message);
    }
  }
  useEffect(() => {
    find();
    return () => { version.current++; };
  }, []);

  return form([
    el("div", { class: "search-person", ref: search },
      field("Buscar destinatario", "search", { maxlength: 100 }),
      button("Buscar", find)),
    select("Destinatario", "recipient_id", withBlank(candidates), "", required),
    el("p", { class: "muted", role: "status" }, message),
    select("Categoría", "category_id", withBlank(available), "", required),
    field("¿Qué quieres reconocer?", "body", { rows: 5, required: true }, "textarea"),
  ], "Publicar Kudo", async (values) => {
    const result = await ctx.api("/kudos", "POST", {
      recipient_id: Number(values.recipient_id), category_id: Number(values.category_id), body: values.body,
    });
    close();
    ctx.announce("Reconocimiento publicado.");
    ctx.navigate(`kudo/${result.id}`);
  });
}

function compose(ctx, categories) {
  const available = categories.filter((category) => category.active);
  if (!available.length) {
    ctx.announce("Se requiere una categoría activa para publicar.");
    return;
  }
  ctx.dialog("Publicar un reconocimiento", (close) => <PublicarKudo {...{ ctx, available, close }} />);
}

export async function feed(ctx, personal = false) {
  const query = new URLSearchParams(ctx.query);
  const direction = query.get("vista") === "emitidos" ? "emitidos" : "recibidos";
  if (personal) query.set(direction === "emitidos" ? "sent" : "received", ctx.user.id);
  const [result, allCategories] = await Promise.all([ctx.api(listUrl("/kudos", query)), ctx.api("/categories")]);
  const publish = button("Dar un Kudo", () => compose(ctx, allCategories), "primary");
  const heading = section(personal ? "Mis reconocimientos" : "Comunidad", publish);
  return page(heading,
    personal && el("div", { class: "tabs", "aria-label": "Tipo de historial" }, ["recibidos", "emitidos"].map((item) =>
      el("a", { href: `#historial?vista=${item}`, "aria-current": direction === item ? "page" : null }, item))),
    !personal && (query.has("sent") || query.has("received")) && link("← Quitar filtro de persona", "#inicio", "button secondary"),
    result.items.length ? el("div", { class: "grid" }, result.items.map(kudoCard))
      : empty("Sin Kudos para esta consulta."),
    pager(personal ? "#historial" : "#inicio", ctx.query, result.next));
}

function Valoraciones({ ctx, id }) {
  const [pagina, setPagina] = useState(null);
  const [error, setError] = useState("");
  const [cargando, setCargando] = useState(false);
  async function cargar(cursor = null) {
    setCargando(true);
    setError("");
    try {
      const ruta = `/content/${id}/ratings`;
      setPagina(await ctx.api(cursor ? `${ruta}?after=${cursor}` : ruta));
    } catch (fallo) {
      setError(fallo.message);
    } finally {
      setCargando(false);
    }
  }
  useEffect(() => { cargar(); }, [id]);

  return <>
    {cargando && <p role="status">Cargando valoraciones…</p>}
    {error && <p role="alert">{error}</p>}
    {pagina && table(["Estrellas", "Fecha", "Última modificación"],
      pagina.items.map((fila) => [`${fila.stars} ★`, date(fila.created_at), date(fila.updated_at)]), "Valoraciones")}
    {pagina && (pagina.next || pagina.items.length) && <div className="actions">
      <button type="button" className="button secondary" disabled={cargando} onClick={() => cargar()}>
        Primera página
      </button>
      {pagina.next && <button type="button" className="button secondary" disabled={cargando} onClick={() => cargar(pagina.next)}>
        Siguiente página
      </button>}
    </div>}
  </>;
}

function contentActions(ctx, item, kudo) {
  const url = `/content/${item.id}`;
  const actions = [
    button("Responder", () => ctx.dialog("Escribir réplica", (close) => form([
      field("Tu mensaje", "body", { rows: 5, required: true }, "textarea"),
    ], "Publicar réplica", async (data) => {
      await ctx.api(`${url}/replies`, "POST", data);
      close();
      await ctx.refresh("Réplica publicada.");
    }))),
    button(item.my_stars ? "Cambiar valoración" : "Valorar", () => ctx.dialog("Valorar contenido", (close) => form([
      select("Estrellas", "stars", [1, 2, 3, 4, 5].map((n) => [n, `${n} ★`]), item.my_stars || 5, required),
    ], "Guardar valoración", async (data) => {
      await ctx.api(`${url}/rating`, "PUT", { stars: Number(data.stars) });
      close();
      await ctx.refresh("Valoración guardada.");
    }))),
    button("Ver valoraciones", () => ctx.dialog("Valoraciones del contenido",
      () => <Valoraciones ctx={ctx} id={item.id} />), "text"),
    button("Reportar", () => ctx.dialog("Reportar contenido", (close) => form([
      select("Motivo", "reason", [blank, ...Object.entries(reasons)], "", required),
      field("Detalles (opcionales)", "detail", { rows: 4, maxlength: 2000 }, "textarea"),
    ], "Enviar reporte", async (data) => {
      await ctx.api(`${url}/reports`, "POST", data);
      close();
      await ctx.refresh("Reporte recibido.");
    })), "text"),
  ];
  if (moderators.includes(ctx.user.role)) {
    actions.push(button("Vetar", () => ctx.dialog("Vetar contenido", (close) => form([
      field("Justificación del veto", "reason", { rows: 4, maxlength: 2000, required: true }, "textarea"),
    ], "Confirmar veto", async (data) => {
      await ctx.api(`${url}/veto`, "POST", data);
      close();
      if (kudo) {
        ctx.announce("Kudo vetado.");
        ctx.navigate("moderacion");
      } else await ctx.refresh("Réplica vetada.");
    })), "danger"));
  }
  return el("div", { class: "actions" }, actions);
}

export async function detail(ctx, id) {
  if (!/^[1-9][0-9]*$/.test(id || "")) throw new Error("Selecciona un reconocimiento válido.");
  const { kudo, replies } = await ctx.api(`/kudos/${id}`);
  const children = new Map();
  for (const reply of replies) {
    if (!children.has(reply.parent_id)) children.set(reply.parent_id, []);
    children.get(reply.parent_id).push(reply);
  }
  const pending = [{ item: kudo, parent: null, level: 0 }];
  const nodes = [];
  while (pending.length) {
    const { item: reply, parent, level } = pending.pop();
    const descendants = children.get(reply.id) || [];
    for (let index = descendants.length - 1; index >= 0; index -= 1) {
      pending.push({ item: descendants[index], parent: reply, level: level + 1 });
    }
    if (!parent) continue;
    nodes.push(el("article", { class: `reply indent-${Math.min(level - 1, 3)}`, id: `reply-${reply.id}` },
      el("div", {}, el("strong", {}, reply.author), el("small", {}, `En respuesta a ${parent.author} · ${date(reply.created_at)}`)),
      el("p", { class: "body-copy" }, reply.body),
      el("div", { class: "card-footer" }, el("span", { class: "stars" }, ratingLabel(reply)),
        contentActions(ctx, reply, false))));
  }
  return page(section("Conversación", link("← Comunidad", "#inicio", "button secondary")),
    el("article", { class: "panel detail-kudo" },
      el("div", { class: "card-meta" }, chip(kudo.category_name, "green"), el("time", { datetime: kudo.created_at }, date(kudo.created_at))),
      el("p", {}, el("strong", {}, kudo.author), " reconoció a ", el("strong", {}, kudo.recipient)),
      el("p", { class: "body-copy" }, kudo.body),
      el("div", { class: "card-footer" }, el("span", { class: "stars" }, ratingLabel(kudo)),
        contentActions(ctx, kudo, true))),
    panel(`Réplicas · ${replies.length}`, replies.length ? el("div", { class: "thread" }, nodes)
      : empty("Sin réplicas.")));
}
