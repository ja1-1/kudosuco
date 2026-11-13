import { categoryManagers, chip, disclosure, el, empty, field, form, section } from "../componentes/ui.jsx";
import { page, panel } from "./presentacion.js";

function categoryForm(ctx, item = null) {
  const fields = [
    field("Nombre", "name", { value: item?.name, maxlength: 100, required: true }),
    field("Descripción", "description", { maxlength: 500, value: item?.description }),
    field("Disponible para nuevos Kudos", "active", { type: "checkbox", checked: item ? Boolean(item.active) : true }),
  ];
  return form(fields, "Guardar categoría", async (data) => {
    await ctx.api(item ? `/categories/${item.id}` : "/categories", item ? "PATCH" : "POST", {
      ...data, active: Boolean(data.active),
    });
    await ctx.refresh("Categoría guardada.");
  });
}

export async function categories(ctx) {
  const items = await ctx.api("/categories");
  const canEdit = categoryManagers.includes(ctx.user.role);
  return page(section("Categorías"),
    canEdit && disclosure("Crear categoría", categoryForm(ctx)),
    items.length ? el("div", { class: "grid" }, items.map((item) =>
      panel(item.name, chip(item.active ? "Activa" : "Inactiva", item.active ? "green" : "gold"),
        item.description && el("p", { class: "muted" }, item.description),
        canEdit && disclosure("Editar categoría", categoryForm(ctx, item)))))
      : empty("Sin categorías."));
}
