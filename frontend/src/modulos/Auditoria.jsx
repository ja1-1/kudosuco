import { date, disclosure, el, section, table } from "../componentes/ui.jsx";
import { page, listUrl, pager } from "./presentacion.js";

export function auditTable(items) {
  return table(["Fecha", "Evento", "Autor", "Objeto", "Datos"], items.map((row) => [
    date(row.occurred_at), row.event, row.actor_id ? `#${row.actor_id}` : "Instalación",
    `${row.subject} #${row.subject_id}`,
    disclosure("Ver datos", el("pre", {}, JSON.stringify(row.facts, null, 2))),
  ]), "Eventos de auditoría");
}

export async function audit(ctx) {
  const result = await ctx.api(listUrl("/audit", ctx.query));
  return page(section("Auditoría"), auditTable(result.items), pager("#auditoria", ctx.query, result.next));
}
