import { chip, date, disclosure, el, empty, field, form, governing, roles, section, select, statuses, table } from "../componentes/ui.jsx";
import { blank, asOptions, withBlank, page, panel, required } from "./presentacion.js";

function accountActions(ctx, account) {
  const url = `/accounts/${account.id}`;
  const actions = [];
  if (account.status === "PENDING") {
    actions.push(form([], "Aprobar solicitud", async () => {
      await ctx.api(`${url}/approve`, "POST");
      await ctx.refresh("Solicitud aprobada.");
    }));
  }
  if (account.status === "APPROVED" && account.email_verified && account.phone_verified) {
    actions.push(form(account.occupant_id ? [
      el("p", { class: "muted" }, `Se cerrará la asignación vigente de la cuenta #${account.occupant_id}.`),
      field("Confirmo el reemplazo", "confirm", { type: "checkbox", required: true }),
    ] : [], account.occupant_id ? "Confirmar relevo" : "Activar cuenta", async () => {
      await ctx.api(`${url}/activate`, "POST", { replaces: account.occupant_id });
      await ctx.refresh("Cuenta activada.");
    }));
  }
  if (account.status !== "DEACTIVATED") {
    const transitions = [["deactivate", "Desactivar definitivamente"]];
    if (["ACTIVE", "APPROVED"].includes(account.status)) transitions.unshift(["suspend", "Suspender"]);
    if (account.status === "SUSPENDED") transitions.unshift(["restore", "Restablecer a aprobada"]);
    actions.push(form([
      select("Acción", "action", [blank, ...transitions], "", required),
      field("Motivo", "reason", { required: true, maxlength: 500 }),
    ], "Aplicar cambio", async (data) => {
      await ctx.api(`${url}/status`, "POST", data);
      await ctx.refresh("Estado actualizado.");
    }, "form-grid"));
  }
  return disclosure(`#${account.id} · ${account.name} · ${roles[account.role]} · ${statuses[account.status]}`,
    el("p", { class: "muted" }, `${account.email} · ${account.phone}`),
    el("p", {}, `Correo ${account.email_verified ? "verificado" : "pendiente"} · Celular ${account.phone_verified ? "verificado" : "pendiente"}`),
    actions);
}

function editUnit(ctx, row, name, url) {
  return disclosure(row.name, form([
    field(name, "name", { value: row.name, maxlength: 120, required: true }),
  ], "Guardar", async (values) => {
    await ctx.api(`${url}/${row.id}`, "PATCH", values);
    await ctx.refresh("Nombre actualizado.");
  }));
}

export async function organization(ctx) {
  const user = ctx.user;
  const [org, accounts] = await Promise.all([
    ctx.api("/organization"), governing.includes(user.role) ? ctx.api("/accounts") : [],
  ]);
  const administrator = user.role === "ADMIN";
  const programsEditable = ["ADMIN", "ACADEMIC", "FACULTY"].includes(user.role);
  const scopedFaculties = org.faculties.filter((row) => user.role !== "FACULTY" || row.id === user.faculty_id);
  const writablePrograms = org.programs.filter((row) => user.role !== "FACULTY" || row.faculty_id === user.faculty_id);
  const facultyNames = new Map(asOptions(org.faculties));
  const programNames = new Map(asOptions(org.programs));
  const unitEditor = el("div", { class: "grid two" },
    administrator && panel("Gestionar facultades", disclosure("Crear facultad", form([
      field("Nombre de facultad", "name", { required: true, maxlength: 120 }),
    ], "Crear facultad", async (values) => {
      await ctx.api("/faculties", "POST", values);
      await ctx.refresh("Facultad creada. Agrega un programa para habilitar su registro.");
    })), org.faculties.map((row) => editUnit(ctx, row, "Nombre de facultad", "/faculties"))),
    programsEditable && panel("Gestionar programas", disclosure("Crear programa", form([
      field("Nombre del programa", "name", { required: true, maxlength: 120 }),
      select("Facultad", "faculty_id", withBlank(scopedFaculties), user.faculty_id || "", required),
    ], "Crear programa", async (values) => {
      await ctx.api("/programs", "POST", { ...values, faculty_id: Number(values.faculty_id) });
      await ctx.refresh("Programa creado.");
    })), writablePrograms.map((row) => editUnit(ctx, row, "Nombre de programa", "/programs"))));
  return page(section("Organización"),
    el("div", { class: "grid two" },
      panel("Autoridades institucionales", table(["Cargo", "Persona"], org.university.map((row) => [roles[row.role], row.name]), "Autoridades")),
      panel("Facultades", table(["Facultad", "Estado", "Coordinador responsable"], org.faculties.map((row) => [
        row.name, row.operational ? "Con programas" : "En creación", row.coordinator_name || "—",
      ]), "Facultades"))),
    panel("Programas académicos", table(["Programa", "Facultad", "Coordinador responsable"], org.programs.map((row) => [
      row.name, facultyNames.get(row.faculty_id), row.coordinator_name || "—",
    ]), "Programas")),
    (administrator || programsEditable) && unitEditor,
    governing.includes(user.role) && panel("Cuentas en mi ámbito",
      administrator && disclosure("Registrar administrador o rector", form([
        select("Rol", "role", [["ADMIN", roles.ADMIN], ["RECTOR", roles.RECTOR]], "RECTOR", required),
        field("Nombre completo", "name", { required: true, maxlength: 120 }),
        field("Correo institucional", "email", { type: "email", required: true, maxlength: 254 }),
        field("Celular", "phone", { type: "tel", required: true, placeholder: "+573001234567" }),
        field("Contraseña inicial", "password", { type: "password", autocomplete: "new-password", required: true,
          minlength: 12, maxlength: 128 }),
      ], "Registrar cuenta", async (values) => {
        await ctx.api("/accounts", "POST", values);
        await ctx.refresh("Cuenta registrada y aprobada. Faltan las dos verificaciones.");
      }, "form-grid")),
      accounts.length ? accounts.map((account) => accountActions(ctx, account))
        : empty("Sin cuentas en tu ámbito.")),
    user.role === "PROGRAM" && panel("Vínculos de mi programa",
      table(["ID", "Persona", "Rol", "Desde", "Hasta", "Acción"], org.roster.map((row) => [
        row.id, row.name, roles[row.role], date(row.started_at), date(row.ended_at),
        !row.ended_at && row.status !== "DEACTIVATED" && form([], "Desvincular", async () => {
          await ctx.api(`/accounts/${row.id}/memberships/${user.program_id}`, "PUT", { active: false });
          await ctx.refresh("Vínculo cerrado.");
        }),
      ]), "Vínculos del programa")),
    governing.includes(user.role) && panel("Historial de cargos en mi ámbito",
      table(["Persona", "Cargo", "Unidad", "Desde", "Hasta"], org.assignments.map((row) => [
        row.name, roles[row.role], facultyNames.get(row.faculty_id) || programNames.get(row.program_id) || "Universidad",
        date(row.started_at), row.ended_at ? date(row.ended_at) : chip("Vigente"),
      ]), "Asignaciones")),
    administrator && panel("Configuración global", form([
      field("Nombre visible", "platform_name", { value: org.settings.platform_name, required: true, maxlength: 80 }),
      field("Permitir registro público", "registration_open", { type: "checkbox", checked: Boolean(org.settings.registration_open) }),
    ], "Guardar configuración", async (values) => {
      await ctx.api("/settings", "PATCH", { ...values, registration_open: Boolean(values.registration_open) });
      await ctx.refresh("Configuración actualizada.");
    }, "form-grid")));
}
