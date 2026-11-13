import { chip, date, disclosure, el, empty, field, form, reasons, roles, section, select, statuses } from "../componentes/ui.jsx";
import { blank, page, panel, required, listUrl, pager } from "./presentacion.js";

export async function moderation(ctx) {
  const tab = ctx.query.get("vista") === "vetos" ? "vetos" : "reportes";
  const isHistory = tab === "vetos";
  const result = await ctx.api(listUrl(isHistory ? "/moderation/vetos" : "/moderation/reports", ctx.query));
  const cards = result.items.map((item) => {
    const kind = item.kind === "KUDO" ? "Kudo" : "Réplica";
    if (isHistory) {
      return panel(`${kind} #${item.content_id}`, chip("Vetado", "red"),
        el("p", { class: "muted" }, `${item.moderator} · ${roles[item.moderator_role]} · ${date(item.created_at)}`),
        el("p", {}, item.reason), disclosure("Ver contenido conservado", el("p", { class: "body-copy" }, item.body)));
    }
    const endpoint = `/moderation/reports/${item.id}`;
    return panel(`Reporte #${item.id} · ${kind} #${item.content_id}`, chip(statuses[item.state], "gold"),
      el("p", { class: "muted" }, `${item.reporter} · ${date(item.created_at)}`),
      el("p", {}, el("strong", {}, reasons[item.reason]), item.detail && ` · ${item.detail}`),
      disclosure("Ver evidencia", el("p", {}, `Autor: ${item.author}`), el("p", { class: "body-copy" }, item.body)),
      item.decision_reason && el("p", {}, `Decisión: ${item.decision_reason}`),
      item.state === "PENDING" && form([], "Iniciar revisión", async () => {
        await ctx.api(endpoint, "POST", { action: "start" });
        await ctx.refresh("Revisión iniciada.");
      }),
      item.state === "IN_REVIEW" && form([
        select("Decisión", "action", [blank, ["reject", "Rechazar reporte"],
          ["accept", item.content_state === "VETOED" ? "Aceptar reporte" : "Aceptar y vetar"]], "", required),
        field("Justificación", "reason", { required: true, rows: 3, maxlength: 2000 }, "textarea"),
      ], "Registrar decisión", async (data) => {
        await ctx.api(endpoint, "POST", data);
        await ctx.refresh("Decisión registrada.");
      }));
  });
  return page(section("Moderación"),
    el("div", { class: "tabs", "aria-label": "Sección de moderación" }, ["reportes", "vetos"].map((item) =>
      el("a", { href: `#moderacion?vista=${item}`, "aria-current": tab === item ? "page" : null }, item))),
    cards.length ? el("div", { class: "grid" }, cards) : empty(isHistory ? "Sin vetos." : "Sin reportes."),
    pager("#moderacion", ctx.query, result.next));
}
