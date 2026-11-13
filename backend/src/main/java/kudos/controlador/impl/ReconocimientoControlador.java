package kudos.controlador.impl;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import kudos.dto.Solicitud;
import kudos.servicio.fachada.KudosFachada;
import kudos.servicio.ReconocimientoServicio;

import static kudos.dominio.ReglasKudos.identificador;
import static kudos.servicio.fachada.KudosFachada.Acceso.*;

@RestController
@RequestMapping("/api")
public class ReconocimientoControlador {
    private final KudosFachada fachada;
    private final JsonMapper json;
    private final ReconocimientoServicio reconocimiento;

    public ReconocimientoControlador(KudosFachada fachada, JsonMapper json, ReconocimientoServicio reconocimiento) {
        this.fachada = fachada;
        this.json = json;
        this.reconocimiento = reconocimiento;
    }

    @GetMapping("/people")
    Object personas(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, false, actor -> 
                reconocimiento.personas(s.filtros()));
    }

    @GetMapping("/kudos")
    Object muro(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, false, actor -> 
                reconocimiento.muro(actor, s.filtros()));
    }

    @PostMapping("/kudos")
    Object publicar(HttpServletRequest http) {
        var s = leerPublicacion(http);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                ResponseEntity.status(201).body(reconocimiento.publicar(actor, s.datos())));
    }

    @GetMapping("/kudos/{id:[1-9][0-9]{0,18}}")
    Object detalle(HttpServletRequest http, @PathVariable long id) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                reconocimiento.detalle(actor, identificador(id, "Kudo")));
    }

    @PostMapping("/content/{id:[1-9][0-9]{0,18}}/replies")
    Object responder(HttpServletRequest http, @PathVariable long id) {
        var s = leerPublicacion(http);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                ResponseEntity.status(201).body(reconocimiento.responder(actor, identificador(id, "Contenido"), s.datos())));
    }

    @PutMapping("/content/{id:[1-9][0-9]{0,18}}/rating")
    Object valorar(HttpServletRequest http, @PathVariable long id) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                reconocimiento.valorar(actor, identificador(id, "Contenido"), s.datos()));
    }

    @GetMapping("/content/{id:[1-9][0-9]{0,18}}/ratings")
    Object valoraciones(HttpServletRequest http, @PathVariable long id) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, false, actor ->
                reconocimiento.valoraciones(identificador(id, "Contenido"), s.filtros()));
    }

    private Solicitud leerPublicacion(HttpServletRequest http) {
        var solicitud = Solicitud.metadatos(http);
        // Comprueba el acceso antes de leer el cuerpo y antes de iniciar la transacción de escritura.
        fachada.ejecutar(solicitud, ACTIVO, false, actor -> null);
        return solicitud.leerPublicacion(http);
    }
}
