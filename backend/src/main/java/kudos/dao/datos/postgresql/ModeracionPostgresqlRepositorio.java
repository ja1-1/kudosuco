package kudos.dao.datos.postgresql;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

import kudos.servicio.repositorio.ModeracionRepositorio;

import static kudos.dao.datos.postgresql.KudosPostgresqlDAO.ahora;

@Repository
public class ModeracionPostgresqlRepositorio implements ModeracionRepositorio {
    private final KudosPostgresqlDAO dao;

    public ModeracionPostgresqlRepositorio(KudosPostgresqlDAO dao) {
        this.dao = dao;
    }

    @Override
    public long crearReporte(long contenido, long reportante, String motivo, String detalle) {
        return dao.insertar("""
                INSERT INTO reports(content_id,reporter_id,reason,detail,state,created_at)
                VALUES (?,?,?,?,'PENDING',?)
                """, contenido, reportante, motivo, detalle, ahora());
    }

    @Override
    public Optional<Reporte> buscarReporte(long id) {
        var fila = dao.uno("SELECT content_id,state FROM reports WHERE id=?", id);
        return Optional.ofNullable(fila)
                .map(datos -> new Reporte((long) datos.get("content_id"), (String) datos.get("state")));
    }

    @Override
    public void iniciarRevision(long reporte, long revisor, String estado) {
        dao.ejecutar("UPDATE reports SET state=?,reviewed_by=?,reviewed_at=? WHERE id=?",
                estado, revisor, ahora(), reporte);
    }

    @Override
    public void cerrarRevision(long reporte, long moderador, String estado, String motivo) {
        dao.ejecutar("UPDATE reports SET state=?,decided_by=?,decided_at=?,decision_reason=? WHERE id=?",
                estado, moderador, ahora(), motivo, reporte);
    }

    @Override
    public Set<String> estadosAbiertos(long contenido) {
        return dao.todos("SELECT state FROM reports WHERE content_id=? AND state IN ('PENDING','IN_REVIEW')", contenido)
                .stream().map(fila -> (String) fila.get("state")).collect(Collectors.toSet());
    }

    @Override
    public void guardarVeto(long contenido, long moderador, String rol, Long reporte, String motivo) {
        dao.ejecutar("""
                INSERT INTO vetos(content_id,moderator_id,moderator_role,report_id,reason,created_at)
                VALUES (?,?,?,?,?,?)
                """, contenido, moderador, rol, reporte, motivo, ahora());
    }

    @Override
    public List<Map<String, Object>> listarReportes(Long antes, int limite) {
        return dao.todos("""
                SELECT r.*,c.kind,c.body,coalesce(c.root_id,c.id) AS kudo_id,c.state AS content_state,
                       a.name AS reporter,author.name AS author
                FROM reports r JOIN content c ON c.id=r.content_id JOIN accounts a ON a.id=r.reporter_id
                JOIN accounts author ON author.id=c.author_id
                WHERE (?::bigint IS NULL OR r.id<?) ORDER BY r.id DESC LIMIT ?
                """, antes, antes, limite);
    }

    @Override
    public List<Map<String, Object>> listarVetos(Long antes, int limite) {
        return dao.todos("""
                SELECT v.*,c.kind,c.body,a.name AS moderator FROM vetos v
                JOIN content c ON c.id=v.content_id JOIN accounts a ON a.id=v.moderator_id
                WHERE (?::bigint IS NULL OR v.content_id<?) ORDER BY v.content_id DESC LIMIT ?
                """, antes, antes, limite);
    }

    @Override
    public void registrarEvento(String evento, long actor, String sujeto, long id, Map<String, Object> hechos) {
        dao.registrar(evento, actor, sujeto, id, hechos);
    }

    @Override
    public List<Map<String, Object>> consultarAuditoria(long actor, VistaAuditoria vista, Long antes, int limite) {
        String filtro = switch (vista) {
            case PERSONAL -> " AND actor_id=?";
            case INSTITUCIONAL -> " AND subject NOT IN ('content','report')";
            case COMPLETA -> "";
        };
        String consulta = "SELECT * FROM audit WHERE (?::bigint IS NULL OR id<?)" + filtro + " ORDER BY id DESC LIMIT ?";
        var filas = vista == VistaAuditoria.PERSONAL
                ? dao.todos(consulta, antes, antes, actor, limite)
                : dao.todos(consulta, antes, antes, limite);
        for (var fila : filas) {
            fila.put("facts", dao.hechos((String) fila.get("facts")));
        }
        return filas;
    }
}
