package kudos.servicio.repositorio;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Persiste la moderación en la unidad de trabajo abierta por la fachada. */
public interface ModeracionRepositorio {
    record Reporte(long contenido, String estado) {}

    enum VistaAuditoria { PERSONAL, INSTITUCIONAL, COMPLETA }

    long crearReporte(long contenido, long reportante, String motivo, String detalle);

    Optional<Reporte> buscarReporte(long id);

    void iniciarRevision(long reporte, long revisor, String estado);

    void cerrarRevision(long reporte, long moderador, String estado, String motivo);

    Set<String> estadosAbiertos(long contenido);

    void guardarVeto(long contenido, long moderador, String rol, Long reporte, String motivo);

    List<Map<String, Object>> listarReportes(Long antes, int limite);

    List<Map<String, Object>> listarVetos(Long antes, int limite);

    void registrarEvento(String evento, long actor, String sujeto, long id, Map<String, Object> hechos);

    List<Map<String, Object>> consultarAuditoria(long actor, VistaAuditoria vista, Long antes, int limite);
}
