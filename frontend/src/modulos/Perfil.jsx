import { button, chip, date, disclosure, el, field, form, roles, section, statuses, table } from "../componentes/ui.jsx";
import { page, panel, listUrl, pager } from "./presentacion.js";
import { auditTable } from "./Auditoria.jsx";

export async function profile(ctx) {
  const user = ctx.user;
  const history = await ctx.api(listUrl("/me/activity", ctx.query));
  const contactCards = ["email", "phone"].map((channel) => {
    const label = channel === "email" ? "Correo institucional" : "Celular";
    const verified = Boolean(user[`${channel}_verified`]);
    const url = `/me/verification/${channel}`;
    return el("section", { class: "contact" },
      el("h3", {}, label),
      el("p", {}, user[channel], " · ", chip(verified ? "Verificado" : "Pendiente")),
      !verified && button("Solicitar código", async () => {
        try {
          await ctx.api(url, "POST");
          ctx.announce(ctx.options.demo ? "Código en el buzón local. Consulta el manual." : "Código en cola de envío.");
        } catch (error) { ctx.announce(error.message); }
      }, "secondary"),
      !verified && form([field("Código de 8 dígitos", "code", { required: true, maxlength: 8,
        minlength: 8, inputmode: "numeric", autocomplete: "one-time-code" })], "Verificar", async (values) => {
        await ctx.api(url, "PUT", values);
        await ctx.refresh(`${label} verificado.`);
      }));
  });
  const approved = user.status === "APPROVED";
  return page(section(user.name, chip(statuses[user.status]), roles[user.role]),
    panel("Verificación de contactos", contactCards,
      user.status === "PENDING" && el("p", {}, "Pendiente de aprobación institucional."),
      approved && user.email_verified && user.phone_verified && form([], "Activar mi cuenta", async () => {
        await ctx.api("/me/activate", "POST");
        ctx.announce("Cuenta activa.");
        ctx.navigate("inicio");
      })),
    disclosure("Editar perfil", form([
      field("Nombre", "name", { value: user.name, required: true, maxlength: 120 }),
      field("Correo institucional", "email", { value: user.email, type: "email", required: true, maxlength: 254 }),
      field("Celular", "phone", { value: user.phone, type: "tel", required: true }),
      field("Contraseña actual (para cambiar contacto o contraseña)", "current_password",
        { type: "password", autocomplete: "current-password", maxlength: 128 }),
      field("Contraseña nueva (opcional)", "new_password", { type: "password", autocomplete: "new-password",
        minlength: 12, maxlength: 128 }),
    ], "Guardar perfil", async (values) => {
      if (!values.new_password) delete values.new_password;
      await ctx.api("/me", "PATCH", values);
      await ctx.refresh("Perfil actualizado.");
    }, "form-grid")),
    user.memberships.length > 0 && panel("Mis vínculos académicos",
      table(["Programa", "Desde", "Hasta"], user.memberships.map((m) => [m.name, date(m.started_at), date(m.ended_at)]), "Mis vínculos")),
    disclosure("Mi actividad", auditTable(history.items), pager("#perfil", ctx.query, history.next)));
}
