package kudos.controlador.impl;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import kudos.dto.Solicitud;
import kudos.servicio.fachada.KudosFachada;
import kudos.servicio.OrganizacionServicio;

import static kudos.dominio.ReglasKudos.identificador;
import static kudos.servicio.fachada.KudosFachada.Acceso.*;

@RestController
@RequestMapping("/api")
public class OrganizacionControlador {
    private final KudosFachada fachada;
    private final JsonMapper json;
    private final OrganizacionServicio organizacion;

    public OrganizacionControlador(KudosFachada fachada, JsonMapper json, OrganizacionServicio organizacion) {
        this.fachada = fachada;
        this.json = json;
        this.organizacion = organizacion;
    }

    @GetMapping("/organization")
    Object organizacion(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, organizacion::consultar);
    }

    @PostMapping("/faculties")
    Object crearFacultad(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                ResponseEntity.status(201).body(organizacion.guardarFacultad(actor, s.datos(), null)));
    }

    @PatchMapping("/faculties/{id:[1-9][0-9]{0,18}}")
    Object editarFacultad(HttpServletRequest http, @PathVariable long id) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                organizacion.guardarFacultad(actor, s.datos(), identificador(id, "Facultad")));
    }

    @PostMapping("/programs")
    Object crearPrograma(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                ResponseEntity.status(201).body(organizacion.guardarPrograma(actor, s.datos(), null)));
    }

    @PatchMapping("/programs/{id:[1-9][0-9]{0,18}}")
    Object editarPrograma(HttpServletRequest http, @PathVariable long id) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                organizacion.guardarPrograma(actor, s.datos(), identificador(id, "Programa")));
    }

    @PatchMapping("/settings")
    Object ajustar(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                organizacion.ajustar(actor, s.datos()));
    }

    @GetMapping("/categories")
    Object categorias(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, false, actor -> 
                organizacion.categorias());
    }

    @PostMapping("/categories")
    Object crearCategoria(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                ResponseEntity.status(201).body(organizacion.guardarCategoria(actor, s.datos(), null)));
    }

    @PatchMapping("/categories/{id:[1-9][0-9]{0,18}}")
    Object editarCategoria(HttpServletRequest http, @PathVariable long id) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                organizacion.guardarCategoria(actor, s.datos(), identificador(id, "Categoría")));
    }
}
