import { Children, cloneElement, createElement, Fragment, useEffect, useId, useRef, useState } from "react";

export const roles = {
  ADMIN: "Administrador", RECTOR: "Rector", ACADEMIC: "Coordinación académica",
  FACULTY: "Coordinación de facultad", PROGRAM: "Coordinación de programa",
  TEACHER: "Docente", STUDENT: "Estudiante",
};

export const statuses = {
  PENDING: "Pendiente", APPROVED: "Aprobada", ACTIVE: "Activa", SUSPENDED: "Suspendida",
  DEACTIVATED: "Desactivada", VISIBLE: "Visible", REPORTED: "Reportado", IN_REVIEW: "En revisión",
  VETOED: "Vetado", ACCEPTED: "Aceptado", REJECTED: "Rechazado",
};

export const moderators = ["RECTOR", "ACADEMIC"];
export const categoryManagers = ["ADMIN", ...moderators];
export const governing = ["ADMIN", "RECTOR", "ACADEMIC", "FACULTY", "PROGRAM"];

export const reasons = {
  OFFENSIVE: "Lenguaje ofensivo", HARASSMENT: "Acoso", DISCRIMINATION: "Discriminación",
  SPAM: "Spam", FALSE_INFORMATION: "Información falsa", OTHER: "Otro motivo",
};

const formatDate = new Intl.DateTimeFormat("es-CO", { dateStyle: "medium", timeStyle: "short" });
export const date = (value) => value ? formatDate.format(new Date(value)) : "—";

const reactAttrs = {
  class: "className", tabindex: "tabIndex", onclick: "onClick", onchange: "onChange",
  maxlength: "maxLength", minlength: "minLength", datetime: "dateTime",
  inputmode: "inputMode", autocomplete: "autoComplete", value: "defaultValue", checked: "defaultChecked",
};

export function el(tag, attrs = {}, ...children) {
  const props = Object.fromEntries(Object.entries(attrs).map(([key, value]) => [
    tag === "option" && key === "value" ? key : reactAttrs[key] || key, value,
  ]));
  return createElement(tag, props, ...Children.toArray(children));
}

export function button(label, action, style = "secondary") {
  return el("button", { type: "button", class: `button ${style}`, onclick: action }, label);
}

export function link(label, url, style = "") {
  return el("a", { href: url, class: style }, label);
}

export function chip(label) {
  return el("span", { class: "chip" }, label);
}

export function section(title, action = null, description = "") {
  return el("header", { class: "section-heading" },
    el("div", {}, el("h1", {}, title), description && el("p", { class: "muted" }, description)), action);
}

export function empty(title, message = "") {
  return el("div", { class: "empty" }, el("strong", {}, title), message && el("p", { class: "muted" }, message));
}

function Etiqueta({ label, control }) {
  const id = useId();
  return <label className="field">
    <span id={id}>{label}</span>
    {cloneElement(control, { "aria-labelledby": id })}
  </label>;
}

export function field(label, name, attrs = {}, kind = "input") {
  return <Etiqueta label={label} control={el(kind, { name, ...attrs })} />;
}

export function select(label, name, items, selected = "", attrs = {}) {
  const control = el("select", { name, value: String(selected), ...attrs },
    items.map(([value, title]) => el("option", { key: value, value }, title)));
  return createElement(Etiqueta, { key: attrs.key, label, control });
}

function Formulario({ fields, label, callback, className }) {
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(event) {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      const values = new FormData(event.currentTarget);
      await callback(Object.fromEntries(values), values);
    } catch (failure) {
      setError(failure.message || "No se pudo completar la operación.");
    } finally {
      setBusy(false);
    }
  }
  return <form className={`form ${className}`} onSubmit={submit}>
    {fields.map((field, index) => <Fragment key={field.key ?? index}>{field}</Fragment>)}
    <p className="form-error" role="alert" hidden={!error}>{error}</p>
    <div className="actions"><button className="button primary" disabled={busy} type="submit">{label}</button></div>
  </form>;
}

export function form(fields, label, callback, className = "") {
  return <Formulario {...{ fields: Children.toArray(fields), label, callback, className }} />;
}

export function disclosure(title, ...contents) {
  return el("details", { class: "disclosure" }, el("summary", {}, title), contents);
}

export function table(headers, rows, caption) {
  if (!rows.length) return empty("Sin registros.");
  return el("div", { class: "table-wrap", tabindex: "0", role: "region", "aria-label": caption },
    el("table", {},
      el("thead", {}, el("tr", {}, headers.map((header) => el("th", { scope: "col" }, header)))),
      el("tbody", {}, rows.map((row) => el("tr", {}, row.map((cell) => el("td", {}, cell)))))));
}

export function Dialogo({ title, content, close }) {
  const ref = useRef(null);
  const id = useId();
  useEffect(() => {
    const trigger = document.activeElement;
    ref.current.showModal();
    return () => { if (trigger instanceof HTMLElement) trigger.focus(); };
  }, []);
  return <dialog ref={ref} aria-labelledby={id} onClose={close}>
    <div className="dialog-heading">
      <h2 id={id}>{title}</h2>
      {button("Cerrar", close, "text")}
    </div>
    {content(close)}
  </dialog>;
}
