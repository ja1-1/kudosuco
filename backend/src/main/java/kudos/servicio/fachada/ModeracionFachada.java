package kudos.servicio.fachada;

import java.util.Map;

import org.springframework.stereotype.Service;

import kudos.dto.Solicitud;
import kudos.servicio.ModeracionServicio;

import static kudos.dominio.ReglasKudos.identificador;
import static kudos.servicio.fachada.KudosFachada.Acceso.ACTIVO;
import static kudos.servicio.fachada.KudosFachada.Acceso.SESION;

@Service
public class ModeracionFachada {
    private final KudosFachada unidadDeTrabajo;
    private final ModeracionServicio moderacion;

    public ModeracionFachada(KudosFachada unidadDeTrabajo, ModeracionServicio moderacion) {
        this.unidadDeTrabajo = unidadDeTrabajo;
        this.moderacion = moderacion;
    }

    public Map<String, Object> reportar(Solicitud solicitud, long contenido) {
        return unidadDeTrabajo.ejecutar(solicitud, ACTIVO, true, actor ->
                moderacion.reportar(actor, identificador(contenido, "Contenido"), solicitud.datos()));
    }

    public Map<String, Object> revisar(Solicitud solicitud, long reporte) {
        return unidadDeTrabajo.ejecutar(solicitud, ACTIVO, true, actor ->
                moderacion.revisar(actor, identificador(reporte, "Reporte"), solicitud.datos()));
    }

    public Map<String, Object> vetar(Solicitud solicitud, long contenido) {
        return unidadDeTrabajo.ejecutar(solicitud, ACTIVO, true, actor ->
                moderacion.vetar(actor, identificador(contenido, "Contenido"), solicitud.datos()));
    }

    public Map<String, Object> consultarReportes(Solicitud solicitud) {
        return unidadDeTrabajo.ejecutar(solicitud, ACTIVO, false, actor ->
                moderacion.reportes(actor, solicitud.filtros().get("before")));
    }

    public Map<String, Object> consultarVetos(Solicitud solicitud) {
        return unidadDeTrabajo.ejecutar(solicitud, ACTIVO, false, actor ->
                moderacion.vetos(actor, solicitud.filtros().get("before")));
    }

    public Map<String, Object> consultarAuditoria(Solicitud solicitud) {
        return unidadDeTrabajo.ejecutar(solicitud, ACTIVO, false, actor ->
                moderacion.auditoria(actor, solicitud.filtros().get("before"), false));
    }

    public Map<String, Object> consultarActividadPersonal(Solicitud solicitud) {
        return unidadDeTrabajo.ejecutar(solicitud, SESION, false, actor ->
                moderacion.auditoria(actor, solicitud.filtros().get("before"), true));
    }
}
