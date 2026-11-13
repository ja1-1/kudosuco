import { useState } from "react";
import { el, field, form, link, roles, select } from "../componentes/ui.jsx";
import { blank, required, withBlank } from "../modulos/presentacion.js";

const publicRoles = Object.entries(roles).filter(([role]) => !["ADMIN", "RECTOR"].includes(role));

export function Ingreso({ ctx, registering }) {
  const { options } = ctx;
  const [role, setRole] = useState("");
  const email = field("Correo institucional", "email", { type: "email", autocomplete: "username",
    placeholder: `nombre@${options.institution}`, required: true, maxlength: 254 });
  const pass = field("Contraseña", "password", { type: "password", autocomplete: registering ? "new-password" : "current-password",
    required: true, maxlength: 128, minlength: registering ? 12 : undefined });
  const faculties = options.faculties.filter((faculty) => faculty.operational);
  const miembro = ["STUDENT", "TEACHER"].includes(role);
  const unit = role === "FACULTY" ? select("Facultad", "faculty_id", withBlank(faculties), "", { ...required, key: "faculty" })
    : role === "PROGRAM" ? select("Programa", "program_id", withBlank(options.programs), "", { ...required, key: "program" })
    : miembro && <fieldset key="programas" className="program-options">
      <legend>Programas académicos</legend>
      <p>Selecciona al menos uno.</p>
      {options.programs.map(({ id, name }) => <label key={id}>
        <input type="checkbox" name="program_ids" value={id} /> {name}
      </label>)}
    </fieldset>;
  const roleSelect = select("Rol solicitado", "role", [blank, ...publicRoles], "", {
    ...required, value: role, onchange: (event) => setRole(event.target.value),
  });
  const login = form([email, pass], "Iniciar sesión", async (data) => {
    const user = await ctx.api("/login", "POST", data);
    ctx.navigate(user.status === "ACTIVE" ? "inicio" : "perfil");
  });
  const signup = () => form([
    field("Nombre completo", "name", { required: true, maxlength: 120, autocomplete: "name" }),
    email, field("Celular", "phone", { required: true, type: "tel", placeholder: "+573001234567", autocomplete: "tel" }),
    roleSelect, unit, pass,
  ], "Solicitar registro", async (data, fields) => {
    if (data.faculty_id) data.faculty_id = Number(data.faculty_id);
    if (data.program_id) data.program_id = Number(data.program_id);
    const alta = miembro
      ? { ...data, program_ids: fields.getAll("program_ids").map(Number) }
      : data;
    await ctx.api("/register", "POST", alta);
    await ctx.api("/login", "POST", { email: data.email, password: data.password });
    ctx.announce("Registro recibido. Verifica tus canales de contacto.");
    ctx.navigate("perfil");
  });
  const open = options.settings.registration_open;
  return el("section", { class: "access-card" },
    el("h1", {}, registering ? (open ? "Solicitar registro" : "Registro cerrado") : "Iniciar sesión"),
    registering && open && el("p", { class: "muted" }, "Usa un celular con indicativo y una contraseña de al menos 12 caracteres."),
    registering ? open && signup() : login,
    registering ? el("p", {}, link("Volver al ingreso", "#inicio"))
      : open && el("p", {}, link("Solicitar una cuenta", "#registro")));
}
