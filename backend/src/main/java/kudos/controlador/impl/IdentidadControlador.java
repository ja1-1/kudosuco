package kudos.controlador.impl;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import org.springframework.http.ResponseCookie;

import kudos.dto.Solicitud;
import kudos.servicio.fachada.KudosFachada;
import kudos.servicio.IdentidadServicio;
import kudos.servicio.OrganizacionServicio;
import kudos.servicio.fachada.ModeracionFachada;
import kudos.configuracion.KudosConfiguracion;

import static kudos.dominio.ReglasKudos.identificador;
import static kudos.dominio.ReglasKudos.booleano;
import static kudos.servicio.fachada.KudosFachada.Acceso.*;

@RestController
@RequestMapping("/api")
public class IdentidadControlador {
    private final KudosFachada fachada;
    private final JsonMapper json;
    private final IdentidadServicio identidad;
    private final OrganizacionServicio organizacion;
    private final ModeracionFachada moderacion;
    private final KudosConfiguracion config;

    public IdentidadControlador(KudosFachada fachada, JsonMapper json, IdentidadServicio identidad, OrganizacionServicio organizacion, ModeracionFachada moderacion, KudosConfiguracion config) {
        this.fachada = fachada;
        this.json = json;
        this.identidad = identidad;
        this.organizacion = organizacion;
        this.moderacion = moderacion;
        this.config = config;
    }

    @GetMapping("/health")
    Map<String, Boolean> salud() {
        return Map.of("ok", true);
    }

    @GetMapping("/options")
    Object opciones(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, PUBLICO, false, actor -> {
            var opciones = organizacion.opciones();
            opciones.put("institution", config.institution());
            opciones.put("demo", config.demostracion());
            return opciones;
        });
    }

    @PostMapping("/register")
    Object registrar(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, PUBLICO, true, actor -> {
            if (identidad.limitado("register:" + s.remoto(), 35, 3600)) {
                return ResponseEntity.status(429).body(Map.of("error", "Demasiadas solicitudes de registro."));
            }
            return ResponseEntity.status(201).body(identidad.registrar(s.datos(), null, false));
        });
    }

    @PostMapping("/login")
    Object ingresar(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, PUBLICO, true, actor -> {
            var ingreso = identidad.ingresar(s.datos(), s.remoto());
            if (ingreso.error() != null) {
                return ResponseEntity.status(ingreso.estado()).body(Map.of("error", ingreso.error()));
            }
            identidad.cerrarSesion(s.token());
            return ResponseEntity.ok().header("Set-Cookie", cookie(ingreso.token(), 28800)).body(ingreso.cuenta());
        });
    }

    @PostMapping("/logout")
    Object salir(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, PUBLICO, true, actor -> {
            identidad.cerrarSesion(s.token());
            return ResponseEntity.ok().header("Set-Cookie", cookie("", 0)).body(Map.of("ok", true));
        });
    }

    @GetMapping("/me")
    Object perfil(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, SESION, false, actor -> 
                identidad.perfil(actor));
    }

    @PatchMapping("/me")
    Object actualizar(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, SESION, true, actor -> 
                identidad.actualizar(actor, s.datos(), s.token()));
    }

    @PostMapping("/me/activate")
    Object activar(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, SESION, true, actor -> 
                identidad.activar(actor, null, null));
    }

    @PostMapping("/me/verification/{canal:email|phone}")
    Object solicitar(HttpServletRequest http, @PathVariable String canal) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, SESION, true, actor -> 
                identidad.solicitarCodigo(actor, canal));
    }

    @PutMapping("/me/verification/{canal:email|phone}")
    Object verificar(HttpServletRequest http, @PathVariable String canal) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, SESION, true, actor -> {
            return identidad.verificar(actor, canal, s.datos())
                    ? ResponseEntity.ok(Map.of("verified", true))
                    : ResponseEntity.unprocessableContent().body(Map.of("error", "Código incorrecto."));
        });
    }

    @GetMapping("/me/activity")
    Object actividad(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return moderacion.consultarActividadPersonal(s);
    }

    @GetMapping("/accounts")
    Object cuentas(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, identidad::administradas);
    }

    @PostMapping("/accounts")
    Object alta(HttpServletRequest http) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                ResponseEntity.status(201).body(identidad.registrar(s.datos(), actor, false)));
    }

    @PostMapping("/accounts/{id:[1-9][0-9]{0,18}}/approve")
    Object aprobar(HttpServletRequest http, @PathVariable long id) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                identidad.aprobar(actor, identificador(id, "Cuenta")));
    }

    @PostMapping("/accounts/{id:[1-9][0-9]{0,18}}/activate")
    Object activarCuenta(HttpServletRequest http, @PathVariable long id) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> {
            Object reemplazo = s.datos().get("replaces");
            return identidad.activar(identidad.cuenta(identificador(id, "Cuenta")), actor,
                    reemplazo == null ? null : identificador(reemplazo, "Cuenta a reemplazar"));
        });
    }

    @PostMapping("/accounts/{id:[1-9][0-9]{0,18}}/status")
    Object cambiarEstado(HttpServletRequest http, @PathVariable long id) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                identidad.cambiarEstado(actor, identificador(id, "Cuenta"), s.datos()));
    }

    @PutMapping("/accounts/{id:[1-9][0-9]{0,18}}/memberships/{programa:[1-9][0-9]{0,18}}")
    Object vinculo(HttpServletRequest http, @PathVariable long id, @PathVariable long programa) {
        var s = Solicitud.leer(http, json);
        return fachada.ejecutar(s, ACTIVO, true, actor -> 
                identidad.cambiarVinculo(actor, identificador(id, "Cuenta"), identificador(programa, "Programa"),
                        booleano(s.datos().get("active"), "Indica el estado booleano del vínculo.")));
    }

    private String cookie(String token, int vigencia) {
        return ResponseCookie.from(Solicitud.COOKIE, token).path("/").httpOnly(true).sameSite("Strict")
                .secure(config.origin().startsWith("https://")).maxAge(vigencia).build().toString();
    }
}
