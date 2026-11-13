package kudos.servicio;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

import kudos.dao.datos.postgresql.KudosPostgresqlDAO;

import static kudos.dao.datos.postgresql.KudosPostgresqlDAO.ahora;
import static kudos.dominio.ReglaException.exigir;
import static kudos.dominio.ReglasKudos.*;
import static kudos.servicio.Datos.datos;

@Service
public class OrganizacionServicio {
    private final KudosPostgresqlDAO dao;

    public OrganizacionServicio(KudosPostgresqlDAO dao) {
        this.dao = dao;
    }

    public Map<String, Object> opciones() {
        return datos("faculties", dao.todos("""
                SELECT f.id,f.name,EXISTS(SELECT 1 FROM programs p WHERE p.faculty_id=f.id) AS operational
                FROM faculties f ORDER BY f.name
                """),
                "programs", dao.todos("SELECT id,faculty_id,name FROM programs ORDER BY name"),
                "settings", ajustes());
    }

    private Map<String, Object> ajustes() {
        return dao.uno("SELECT platform_name,registration_open FROM settings WHERE id=1");
    }

    public Map<String, Object> consultar(Map<String, Object> actor) {
        var vista = datos("settings", ajustes(),
                "faculties", dao.todos("""
                    SELECT f.id,f.name,EXISTS(SELECT 1 FROM programs p WHERE p.faculty_id=f.id) AS operational,
                           a.name AS coordinator_name
                    FROM faculties f LEFT JOIN accounts a ON a.faculty_id=f.id AND a.role='FACULTY' AND a.status='ACTIVE'
                    ORDER BY f.name
                    """),
                "programs", dao.todos("""
                    SELECT p.id,p.faculty_id,p.name,a.name AS coordinator_name
                    FROM programs p LEFT JOIN accounts a ON a.program_id=p.id AND a.role='PROGRAM' AND a.status='ACTIVE'
                    ORDER BY p.name
                    """));
        vista.put("university", dao.todos("""
                SELECT id,name,role FROM accounts WHERE role IN ('RECTOR','ACADEMIC') AND status='ACTIVE' ORDER BY role
                """));
        vista.put("assignments", asignaciones(actor));
        vista.put("roster", "PROGRAM".equals(actor.get("role")) ? dao.todos("""
                SELECT a.id,a.name,a.role,a.status,m.started_at,m.ended_at FROM memberships m
                JOIN accounts a ON a.id=m.account_id WHERE m.program_id=? ORDER BY m.id DESC
                """, actor.get("program_id")) : List.of());
        if (Set.of("RECTOR", "PROGRAM").contains(actor.get("role"))) {
            dao.registrar("OrganizacionConsultada", actor.get("id"), "account", actor.get("id"), datos());
        }
        return vista;
    }

    private List<Map<String, Object>> asignaciones(Map<String, Object> actor) {
        String consulta = "SELECT h.*,a.name FROM assignments h JOIN accounts a ON a.id=h.account_id WHERE ";
        String orden = " ORDER BY h.id DESC";
        return switch ((String) actor.get("role")) {
            case "ADMIN" -> dao.todos(consulta + "h.role='RECTOR'" + orden);
            case "RECTOR" -> dao.todos(consulta + "h.role='ACADEMIC'" + orden);
            case "ACADEMIC" -> dao.todos(consulta + "h.role='FACULTY'" + orden);
            case "FACULTY" -> dao.todos(consulta + "h.faculty_id=? OR h.program_id IN (SELECT id FROM programs WHERE faculty_id=?)"
                    + orden, actor.get("faculty_id"), actor.get("faculty_id"));
            case "PROGRAM" -> dao.todos(consulta + "h.program_id=?" + orden, actor.get("program_id"));
            default -> dao.todos(consulta + "h.account_id=?" + orden, actor.get("id"));
        };
    }

    public Map<String, Object> guardarFacultad(Map<String, Object> actor, Map<String, Object> entrada, Long id) {
        rol(actor, Set.of("ADMIN"));
        String nombre = texto(entrada.get("name"), "Nombre", 120).strip();
        boolean nueva = id == null;
        if (nueva) {
            id = dao.insertar("INSERT INTO faculties(name,created_at) VALUES (?,?)", nombre, ahora());
        } else {
            exigir(dao.ejecutar("UPDATE faculties SET name=? WHERE id=?", nombre, id) == 1, "Facultad inexistente.", 404);
        }
        dao.registrar(nueva ? "FacultadCreada" : "FacultadModificada", actor.get("id"), "faculty", id, datos("name", nombre));
        return datos("id", id, "name", nombre);
    }

    public Map<String, Object> guardarPrograma(Map<String, Object> actor, Map<String, Object> entrada, Long id) {
        rol(actor, Set.of("ADMIN", "ACADEMIC", "FACULTY"));
        String nombre = texto(entrada.get("name"), "Nombre", 120).strip();
        boolean nuevo = id == null;
        long facultad;
        if (nuevo) {
            facultad = identificador(entrada.get("faculty_id"), "Facultad");
        } else {
            var actual = dao.uno("SELECT faculty_id FROM programs WHERE id=?", id);
            exigir(actual != null, "Programa inexistente.", 404);
            facultad = (long) actual.get("faculty_id");
        }
        exigir(dao.uno("SELECT id FROM faculties WHERE id=?", facultad) != null, "Facultad inexistente.", 404);
        exigir(!"FACULTY".equals(actor.get("role")) || Objects.equals(actor.get("faculty_id"), facultad),
                "El programa está fuera de tu facultad.", 403);
        if (nuevo) {
            id = dao.insertar("INSERT INTO programs(faculty_id,name,created_at) VALUES (?,?,?)", facultad, nombre, ahora());
        } else {
            dao.ejecutar("UPDATE programs SET name=? WHERE id=?", nombre, id);
        }
        dao.registrar(nuevo ? "ProgramaCreado" : "ProgramaModificado", actor.get("id"), "program", id,
                datos("faculty_id", facultad, "name", nombre));
        return datos("id", id, "faculty_id", facultad, "name", nombre);
    }

    public List<Map<String, Object>> categorias() {
        return dao.todos("SELECT * FROM categories ORDER BY name");
    }

    public Map<String, Object> guardarCategoria(Map<String, Object> actor, Map<String, Object> entrada, Long id) {
        rol(actor, ADMIN_CATEGORIAS);
        var actual = id == null ? datos("name", null, "description", "", "active", 1)
                : dao.uno("SELECT * FROM categories WHERE id=?", id);
        exigir(actual != null, "Categoría inexistente.", 404);
        String nombre = texto(entrada.getOrDefault("name", actual.get("name")), "Categoría", 100).strip();
        String descripcion = texto(entrada.getOrDefault("description", actual.get("description")), "Descripción", 500, true).strip();
        boolean antes = bandera(actual.get("active"));
        boolean activa = booleano(entrada.getOrDefault("active", antes), "El estado de categoría debe ser booleano.");
        Object usuario = actor.get("id");
        if (id == null) {
            id = dao.insertar("INSERT INTO categories(name,description,active) VALUES (?,?,?)", nombre, descripcion, activa ? 1 : 0);
            dao.registrar("CategoriaCreada", usuario, "category", id, datos("name", nombre, "active", activa));
        } else {
            dao.ejecutar("UPDATE categories SET name=?,description=?,active=? WHERE id=?", nombre, descripcion, activa ? 1 : 0, id);
            if (!nombre.equals(actual.get("name")) || !descripcion.equals(actual.get("description"))) {
                dao.registrar("CategoriaModificada", usuario, "category", id,
                        datos("before", datos("name", actual.get("name"), "description", actual.get("description")),
                                "after", datos("name", nombre, "description", descripcion)));
            }
            if (antes != activa) {
                dao.registrar(activa ? "CategoriaActivada" : "CategoriaDesactivada", usuario, "category", id, Map.of());
            }
        }
        return datos("id", id, "name", nombre, "description", descripcion, "active", activa);
    }

    public Map<String, Object> ajustar(Map<String, Object> actor, Map<String, Object> entrada) {
        rol(actor, Set.of("ADMIN"));
        var actual = ajustes();
        String nombre = texto(entrada.getOrDefault("platform_name", actual.get("platform_name")), "Nombre de plataforma", 80).strip();
        boolean antes = bandera(actual.get("registration_open"));
        boolean abierto = booleano(entrada.getOrDefault("registration_open", antes), "El estado del registro debe ser booleano.");
        dao.ejecutar("UPDATE settings SET platform_name=?,registration_open=? WHERE id=1", nombre, abierto ? 1 : 0);
        var resultado = datos("platform_name", nombre, "registration_open", abierto);
        if (!nombre.equals(actual.get("platform_name")) || antes != abierto) {
            dao.registrar("ConfiguracionActualizada", actor.get("id"), "settings", 1, resultado);
        }
        return resultado;
    }
}
