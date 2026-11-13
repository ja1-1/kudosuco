package kudos.servicio;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

import kudos.dominio.ReporteDominio;
import kudos.dominio.ReglaException;
import kudos.servicio.repositorio.ModeracionRepositorio;
import kudos.servicio.repositorio.ModeracionRepositorio.VistaAuditoria;

import static kudos.dominio.ReglasKudos.*;
import static kudos.servicio.Datos.*;
import static kudos.servicio.ReconocimientoServicio.estado;

@Service
public class ModeracionServicio {
    private static final Set<String> MOTIVOS = Set.of("OFFENSIVE", "HARASSMENT", "DISCRIMINATION", "SPAM", "FALSE_INFORMATION", "OTHER");
    private final ModeracionRepositorio repositorio;
    private final ReconocimientoServicio reconocimientos;

    public ModeracionServicio(ModeracionRepositorio repositorio, ReconocimientoServicio reconocimientos) {
        this.repositorio = repositorio;
        this.reconocimientos = reconocimientos;
    }

    public Map<String, Object> reportar(Map<String, Object> actor, long id, Map<String, Object> entrada) {
        var item = reconocimientos.contenido(id, true);
        String motivo = opcion(entrada.get("reason"), MOTIVOS, "Selecciona un motivo válido.");
        String detalle = texto(entrada.getOrDefault("detail", ""), "Detalle", 2000, true).strip();
        long reportante = (long) actor.get("id");
        long reporte = repositorio.crearReporte(id, reportante, motivo, detalle);
        reconocimientos.guardarEstado(id, estado(item).reportar());
        repositorio.registrarEvento("KUDO".equals(item.get("kind")) ? "KudoReportado" : "ReplicaReportada",
                reportante, "content", id, datos("report_id", reporte, "reason", motivo));
        return datos("id", reporte);
    }

    public Map<String, Object> reportes(Map<String, Object> actor, Object antes) {
        rol(actor, MODERADORES);
        return pagina(repositorio.listarReportes((Long) antes, 31), 30, "id");
    }

    public Map<String, Object> vetos(Map<String, Object> actor, Object antes) {
        rol(actor, MODERADORES);
        return pagina(repositorio.listarVetos((Long) antes, 31), 30, "content_id");
    }

    public Map<String, Object> revisar(Map<String, Object> actor, long id, Map<String, Object> entrada) {
        rol(actor, MODERADORES);
        var fila = repositorio.buscarReporte(id)
                .orElseThrow(() -> new ReglaException(404, "Reporte inexistente."));
        long contenido = fila.contenido();
        var item = reconocimientos.contenido(contenido, false);
        var reporte = new ReporteDominio(fila.estado());
        long moderador = (long) actor.get("id");
        boolean vetado = "VETOED".equals(item.get("state"));
        String accion = opcion(entrada.get("action"), Set.of("start", "accept", "reject"), "Acción de revisión desconocida.");
        if (accion.equals("start")) {
            String estado = reporte.iniciar();
            repositorio.iniciarRevision(id, moderador, estado);
            if (!vetado) {
                reconocimientos.guardarEstado(contenido, estado(item).revisar());
            }
            repositorio.registrarEvento("RevisionIniciada", moderador, "report", id, datos("content_id", contenido));
            return datos("state", estado);
        }
        String motivo = texto(entrada.get("reason"), "Justificación", 2000).strip();
        String decision = reporte.decidir(accion.equals("accept"));
        repositorio.cerrarRevision(id, moderador, decision, motivo);
        repositorio.registrarEvento("RevisionCerrada", moderador, "report", id, datos("content_id", contenido,
                "previous", fila.estado(), "state", decision, "reason", motivo, "role", actor.get("role")));
        if (accion.equals("accept")) {
            var resultado = vetado ? datos("id", contenido, "content_state", "VETOED")
                    : aplicarVeto(actor, item, motivo, id);
            resultado.put("state", decision);
            return resultado;
        }
        if (vetado) {
            return datos("state", decision, "content_state", "VETOED");
        }
        String estado = estado(item).liberar(repositorio.estadosAbiertos(contenido));
        reconocimientos.guardarEstado(contenido, estado);
        if (estado.equals("VISIBLE")) {
            repositorio.registrarEvento("ContenidoLiberado", moderador, "content", contenido, datos("report_id", id));
        }
        return datos("state", decision, "content_state", estado);
    }

    public Map<String, Object> vetar(Map<String, Object> actor, long id, Map<String, Object> entrada) {
        rol(actor, MODERADORES);
        return aplicarVeto(actor, reconocimientos.contenido(id, false),
                texto(entrada.get("reason"), "Justificación", 2000).strip(), null);
    }

    private Map<String, Object> aplicarVeto(Map<String, Object> actor, Map<String, Object> item, String motivo, Long reporte) {
        long id = (long) item.get("id");
        String estado = estado(item).vetar();
        reconocimientos.guardarEstado(id, estado);
        long moderador = (long) actor.get("id");
        repositorio.guardarVeto(id, moderador, (String) actor.get("role"), reporte, motivo);
        repositorio.registrarEvento("KUDO".equals(item.get("kind")) ? "KudoVetado" : "ReplicaVetada", moderador, "content", id,
                datos("report_id", reporte, "reason", motivo, "role", actor.get("role"), "previous", item.get("state")));
        return datos("id", id, "content_state", estado);
    }

    public Map<String, Object> auditoria(Map<String, Object> actor, Object antes, boolean personal) {
        VistaAuditoria vista = VistaAuditoria.PERSONAL;
        if (!personal) {
            rol(actor, Set.of("ADMIN", "RECTOR", "ACADEMIC"));
            vista = "ADMIN".equals(actor.get("role")) ? VistaAuditoria.INSTITUCIONAL : VistaAuditoria.COMPLETA;
        }
        return pagina(repositorio.consultarAuditoria((long) actor.get("id"), vista, (Long) antes, 51), 50, "id");
    }
}
