package kudos.controlador.impl;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import kudos.dto.Solicitud;
import kudos.servicio.fachada.ModeracionFachada;

@RestController
@RequestMapping("/api")
public class ModeracionControlador {
    private final ModeracionFachada fachada;
    private final JsonMapper json;

    public ModeracionControlador(ModeracionFachada fachada, JsonMapper json) {
        this.fachada = fachada;
        this.json = json;
    }

    @PostMapping("/content/{id:[1-9][0-9]{0,18}}/reports")
    Object reportar(HttpServletRequest http, @PathVariable long id) {
        var solicitud = Solicitud.leer(http, json);
        return ResponseEntity.status(201).body(fachada.reportar(solicitud, id));
    }

    @PostMapping("/content/{id:[1-9][0-9]{0,18}}/veto")
    Object vetar(HttpServletRequest http, @PathVariable long id) {
        var solicitud = Solicitud.leer(http, json);
        return fachada.vetar(solicitud, id);
    }

    @GetMapping("/moderation/reports")
    Object reportes(HttpServletRequest http) {
        var solicitud = Solicitud.leer(http, json);
        return fachada.consultarReportes(solicitud);
    }

    @PostMapping("/moderation/reports/{id:[1-9][0-9]{0,18}}")
    Object revisar(HttpServletRequest http, @PathVariable long id) {
        var solicitud = Solicitud.leer(http, json);
        return fachada.revisar(solicitud, id);
    }

    @GetMapping("/moderation/vetos")
    Object vetos(HttpServletRequest http) {
        var solicitud = Solicitud.leer(http, json);
        return fachada.consultarVetos(solicitud);
    }

    @GetMapping("/audit")
    Object auditoria(HttpServletRequest http) {
        var solicitud = Solicitud.leer(http, json);
        return fachada.consultarAuditoria(solicitud);
    }
}
