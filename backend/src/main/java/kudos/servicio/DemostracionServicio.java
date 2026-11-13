package kudos.servicio;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

import kudos.configuracion.KudosConfiguracion;
import kudos.dao.datos.postgresql.KudosPostgresqlDAO;

import static kudos.dominio.ReglaException.exigir;
import static kudos.servicio.Datos.datos;

@Service
public class DemostracionServicio {
    static final String CLAVE = "1234";
    private static final Usuario[] USUARIOS = {
        new Usuario("admin", "Laura Vélez", "ADMIN", null),
        new Usuario("rector", "Gabriel Restrepo", "RECTOR", null),
        new Usuario("academico", "Isabel Ospina", "ACADEMIC", "RECTOR"),
        new Usuario("facultad", "Tomás Montoya", "FACULTY", "ACADEMIC"),
        new Usuario("programa", "Sara Gil", "PROGRAM", "FACULTY"),
        new Usuario("docente", "Daniela Ríos", "TEACHER", "PROGRAM"),
        new Usuario("estudiante", "Valentina Soto", "STUDENT", "PROGRAM"),
        new Usuario("compa", "Miguel Arias", "STUDENT", "PROGRAM"),
    };

    private record Usuario(String alias, String nombre, String rol, String aprobador) {}

    private final KudosConfiguracion config;
    private final KudosPostgresqlDAO dao;
    private final IdentidadServicio identidad;
    private final OrganizacionServicio organizacion;
    private final ReconocimientoServicio reconocimientos;

    public DemostracionServicio(KudosConfiguracion config, KudosPostgresqlDAO dao, IdentidadServicio identidad,
                                OrganizacionServicio organizacion, ReconocimientoServicio reconocimientos) {
        this.config = config;
        this.dao = dao;
        this.identidad = identidad;
        this.organizacion = organizacion;
        this.reconocimientos = reconocimientos;
    }

    public boolean preparar() {
        exigir(config.demostracion(), "Activa el modo demostración.");
        return dao.transaccion(true, () -> {
            if (dao.uno("SELECT 1 FROM audit WHERE event='IdentidadDemoPreparada' LIMIT 1") != null) {
                actualizarClavesAnteriores();
                return false;
            }
            exigir(dao.uno("SELECT id FROM accounts LIMIT 1") == null, "Usa una base vacía para la demostración.", 409);
            cargar();
            return true;
        });
    }

    private void actualizarClavesAnteriores() {
        var cuentas = dao.todos("""
                SELECT DISTINCT subject_id FROM audit
                WHERE event='IdentidadDemoPreparada' AND subject='account'
                AND NOT EXISTS (SELECT 1 FROM audit WHERE event='ClaveDemoActualizada'
                    OR (event='IdentidadDemoPreparada' AND facts::jsonb @> '{"clave_demo":2}'))
                """);
        for (var fila : cuentas) {
            long id = (long) fila.get("subject_id");
            dao.ejecutar("UPDATE accounts SET password_hash=? WHERE id=?", identidad.hashNuevaClave(CLAVE), id);
            dao.ejecutar("DELETE FROM sessions WHERE account_id=?", id);
            dao.registrar("ClaveDemoActualizada", null, "account", id, Map.of());
        }
    }

    private void cargar() {
        Map<String, Map<String, Object>> actores = new HashMap<>();
        Object facultad = null;
        Object programa = null;
        int indice = 1;
        for (Usuario usuario : USUARIOS) {
            boolean inicial = usuario.rol().equals("ADMIN");
            var registro = identidad.registrarDemostracion(datos("role", usuario.rol(), "name", usuario.nombre(),
                    "email", usuario.alias() + "@" + config.institution(),
                    "phone", String.format(Locale.ROOT, "+573000000%03d", indice++),
                    "faculty_id", facultad, "program_id", programa),
                    usuario.rol().equals("RECTOR") ? actores.get("ADMIN") : null, inicial);
            long id = (long) registro.get("id");
            if (usuario.aprobador() != null) identidad.aprobar(actores.get(usuario.aprobador()), id);
            dao.ejecutar("UPDATE accounts SET email_verified=1,phone_verified=1 WHERE id=?", id);
            dao.registrar("IdentidadDemoPreparada", null, "account", id, Map.of("clave_demo", 2));
            var cuenta = identidad.activar(identidad.cuenta(id), null, null);
            actores.put(usuario.alias(), cuenta);
            actores.put(usuario.rol(), cuenta);
            if (inicial) {
                facultad = organizacion.guardarFacultad(cuenta, datos("name", "Facultad de Ingenierías"), null).get("id");
                programa = organizacion.guardarPrograma(cuenta, datos("name", "Ingeniería de Sistemas", "faculty_id", facultad), null).get("id");
                Object otra = organizacion.guardarFacultad(cuenta, datos("name", "Facultad de Educación"), null).get("id");
                organizacion.guardarPrograma(cuenta, datos("name", "Licenciatura en Educación", "faculty_id", otra), null);
            }
        }
        var admin = actores.get("admin");
        var colaboracion = organizacion.guardarCategoria(admin, datos("name", "Colaboración", "description", "Trabajo en equipo"), null);
        var liderazgo = organizacion.guardarCategoria(admin, datos("name", "Liderazgo", "description", "Iniciativas que inspiran"), null);
        organizacion.guardarCategoria(actores.get("academico"), datos("name", "Excelencia académica", "description", "Logros de aprendizaje"), null);
        var estudiante = actores.get("estudiante");
        var companero = actores.get("compa");
        reconocimientos.publicar(estudiante, datos("recipient_id", companero.get("id"), "category_id", liderazgo.get("id"),
                "body", "Gracias por organizar las sesiones de práctica de esta semana. Abriste el espacio a todo el grupo y ayudaste a que llegáramos con más confianza a la exposición."));
        long primero = (long) reconocimientos.publicar(actores.get("docente"), datos("recipient_id", estudiante.get("id"),
                "category_id", colaboracion.get("id"),
                "body", "Valentina acompañó a sus compañeros en el laboratorio y compartió una guía que facilitó el trabajo de todo el curso. Gracias por hacer del aprendizaje un esfuerzo colectivo.")).get("id");
        long replica = (long) reconocimientos.responder(companero, primero,
                datos("body", "La guía nos ayudó a reproducir los ejemplos. ¡Gracias!")).get("id");
        reconocimientos.responder(estudiante, replica,
                datos("body", "Me alegra que haya servido. La próxima semana comparto la versión actualizada."));
        reconocimientos.valorar(companero, primero, datos("stars", 5));
    }
}
