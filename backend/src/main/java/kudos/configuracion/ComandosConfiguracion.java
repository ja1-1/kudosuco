package kudos.configuracion;

import java.util.Arrays;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kudos.dao.datos.postgresql.KudosPostgresqlDAO;
import kudos.infraestructura.notificacion.Notificaciones;
import kudos.servicio.DemostracionServicio;
import kudos.servicio.IdentidadServicio;

import static kudos.dominio.ReglaException.exigir;
import static kudos.servicio.Datos.datos;

@Configuration
public class ComandosConfiguracion {
    @Bean
    @DependsOnDatabaseInitialization
    ApplicationRunner comandos(KudosPostgresqlDAO dao, KudosConfiguracion config, IdentidadServicio identidad,
                               DemostracionServicio demo, Notificaciones notificaciones) {
        if (!config.demostracion()) {
            exigir(dao.uno("SELECT 1 FROM audit WHERE event='IdentidadDemoPreparada' LIMIT 1") == null,
                    "Usa una base distinta para el despliegue real.");
        }
        return argumentos -> {
            if (!argumentos.containsOption("kudos.command")) return;
            switch (opcion(argumentos, "kudos.command")) {
                case "seed-demo" -> System.out.println(demo.preparar() ? "Demostración cargada." : "Demostración disponible.");
                case "inbox" -> {
                    exigir(config.demostracion(), "El buzón local requiere KUDOS_DEMO=1.");
                    String destino = opcion(argumentos, "to").toLowerCase(Locale.ROOT);
                    var filas = dao.todos("SELECT channel,code FROM notifications WHERE destination=? AND expires>?",
                            destino, IdentidadServicio.epoch());
                    if (filas.isEmpty()) System.out.println("Sin códigos vigentes.");
                    filas.forEach(fila -> System.out.println(fila.get("channel") + ": " + fila.get("code")));
                }
                case "bootstrap-admin" -> {
                    var consola = System.console();
                    exigir(consola != null, "Ejecuta el comando desde una terminal interactiva.");
                    String nombre = opcion(argumentos, "name");
                    String correo = opcion(argumentos, "email");
                    String celular = opcion(argumentos, "phone");
                    char[] clave = consola.readPassword("Contraseña inicial: ");
                    exigir(clave != null, "Contraseña inicial requerida.");
                    try {
                        dao.transaccion(true, () -> identidad.registrar(datos("role", "ADMIN", "name", nombre,
                                "email", correo, "phone", celular, "password", new String(clave)), null, true));
                        System.out.println("Administrador aprobado. Verifica ambos canales y activa la cuenta.");
                    } finally {
                        Arrays.fill(clave, '\0');
                    }
                }
                case "notify" -> {
                    exigir(!config.demostracion(), "El envío real requiere desactivar KUDOS_DEMO.");
                    System.out.println("Notificaciones entregadas: " + notificaciones.entregar());
                }
                default -> throw new IllegalArgumentException("Comando de Kudos desconocido.");
            }
        };
    }

    private static String opcion(ApplicationArguments argumentos, String nombre) {
        var valores = argumentos.getOptionValues(nombre);
        exigir(valores != null && valores.size() == 1 && !valores.getFirst().isBlank(), "Se requiere --" + nombre + "=valor.");
        return valores.getFirst();
    }
}
