import { el, link } from "../componentes/ui.jsx";

export const blank = ["", "Selecciona una opción"];
export const asOptions = (rows) => rows.map((row) => [row.id, row.name]);
export const withBlank = (rows) => [blank, ...asOptions(rows)];
export const page = (heading, ...content) => el("div", { class: "page" }, heading, content);
export const panel = (title, ...content) => el("section", { class: "panel" }, el("h2", {}, title), content);
export const required = { required: true };

export function listUrl(path, query) {
  const encoded = query.toString();
  return encoded ? `${path}?${encoded}` : path;
}

export function pager(hash, query, next) {
  if (!next) return null;
  const params = new URLSearchParams(query);
  params.set("before", next);
  return link("Ver página siguiente →", listUrl(hash, params), "button secondary");
}
