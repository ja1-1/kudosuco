package kudos.infraestructura.notificacion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import kudos.dao.datos.postgresql.KudosPostgresqlDAO;

import static kudos.servicio.IdentidadServicio.epoch;

@Component
public class Notificaciones {
    private final KudosPostgresqlDAO dao;
    private final JavaMailSender correo;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Notificaciones(KudosPostgresqlDAO dao, JavaMailSender correo, JsonMapper json) {
        this.dao = dao;
        this.correo = correo;
        this.json = json;
    }

    @Value("${KUDOS_SMTP_FROM:}")
    private String remitente;

    @Value("${KUDOS_SMS_URL:}")
    private String url;

    @Value("${KUDOS_SMS_TOKEN:}")
    private String token;

    public int entregar() throws InterruptedException {
        if (remitente.isBlank() || !url.startsWith("https://") || token.isBlank()) {
            throw new IllegalStateException("Configura KUDOS_SMTP_FROM, KUDOS_SMS_URL HTTPS y KUDOS_SMS_TOKEN.");
        }
        var pendientes = dao.transaccion(true, () -> {
            long momento = epoch();
            dao.ejecutar("DELETE FROM notifications WHERE expires<=?", momento);
            dao.ejecutar("DELETE FROM verification WHERE requested<=?", momento - 600);
            dao.ejecutar("DELETE FROM rate_limits WHERE started<=?", momento - 3600);
            return dao.todos("SELECT * FROM notifications ORDER BY id LIMIT 100");
        });
        int enviadas = 0;
        for (var fila : pendientes) {
            if ((long) fila.get("expires") <= epoch()) continue;
            String canal = (String) fila.get("channel");
            try {
                if (canal.equals("email")) enviarCorreo(fila);
                else enviarSms(fila);
            } catch (RuntimeException | IOException error) {
                LoggerFactory.getLogger(getClass()).warn("Notificación pendiente en canal {} ({})",
                        canal, error.getClass().getSimpleName());
                continue;
            }
            dao.transaccion(true, () -> dao.ejecutar("""
                    DELETE FROM notifications WHERE id=? AND account_id=? AND channel=? AND expires=? AND code=?
                    """, fila.get("id"), fila.get("account_id"), canal, fila.get("expires"), fila.get("code")));
            enviadas++;
        }
        return enviadas;
    }

    private void enviarCorreo(Map<String, Object> fila) {
        var mensaje = new SimpleMailMessage();
        mensaje.setFrom(remitente);
        mensaje.setTo((String) fila.get("destination"));
        mensaje.setSubject("Código de verificación UCO Kudos");
        mensaje.setText("Código: " + fila.get("code") + ". Caduca diez minutos después de la solicitud.");
        correo.send(mensaje);
    }

    private void enviarSms(Map<String, Object> fila) throws IOException, InterruptedException {
        var datos = Map.of("to", fila.get("destination"), "message", "UCO Kudos: " + fila.get("code"));
        var solicitud = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "kudos-" + fila.get("account_id") + "-" + fila.get("expires") + "-" + fila.get("id"))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(datos))).build();
        int estado = http.send(solicitud, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (estado < 200 || estado >= 300) throw new IOException("El proveedor rechazó el mensaje.");
    }
}
