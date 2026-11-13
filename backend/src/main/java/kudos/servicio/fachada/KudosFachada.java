package kudos.servicio.fachada;

import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;

import kudos.dao.datos.postgresql.KudosPostgresqlDAO;
import kudos.dto.Solicitud;
import kudos.servicio.IdentidadServicio;

import static kudos.dominio.ReglaException.exigir;

@Service
public class KudosFachada {
    public enum Acceso { PUBLICO, SESION, ACTIVO }

    private final KudosPostgresqlDAO dao;
    private final IdentidadServicio identidad;

    public KudosFachada(KudosPostgresqlDAO dao, IdentidadServicio identidad) {
        this.dao = dao;
        this.identidad = identidad;
    }

    public <T> T ejecutar(Solicitud solicitud, Acceso acceso, boolean escritura,
                          Function<Map<String, Object>, T> operacion) {
        return dao.transaccion(escritura, () -> {
            if (acceso == Acceso.PUBLICO) {
                return operacion.apply(null);
            }
            var actor = identidad.sesion(solicitud.token());
            exigir(actor != null, "Inicia sesión para continuar.", 401);
            if (acceso == Acceso.ACTIVO) {
                exigir("ACTIVE".equals(actor.get("status")), "La cuenta aún no está activa.", 403);
            }
            return operacion.apply(actor);
        });
    }
}
