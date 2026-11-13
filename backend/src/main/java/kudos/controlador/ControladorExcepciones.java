package kudos.controlador;

import java.sql.SQLException;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import kudos.dominio.ReglaException;

@RestControllerAdvice
public class ControladorExcepciones {
    @ExceptionHandler(ReglaException.class)
    ResponseEntity<Map<String, String>> regla(ReglaException error) {
        return respuesta(error.estado(), error.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> datos(DataAccessException error) {
        if (error.getMostSpecificCause() instanceof SQLException sql) {
            String codigo = sql.getSQLState();
            if (codigo != null && codigo.startsWith("23")) {
                String mensaje = "La operación entra en conflicto con un registro existente.";
                if ("23505".equals(codigo)) {
                    if (sql.getMessage().contains("accounts_email_key")) mensaje = "El correo ya está registrado.";
                    if (sql.getMessage().contains("accounts_phone_key")) mensaje = "El celular ya está registrado.";
                }
                return respuesta(409, mensaje);
            }
            if ("55P03".equals(codigo) || "40P01".equals(codigo)) {
                return respuesta(503, "Sistema ocupado. Intenta de nuevo.");
            }
        }
        return inesperada(error);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> inesperada(Exception error) {
        if (error instanceof ErrorResponse respuesta && respuesta.getStatusCode().is4xxClientError()) {
            return respuesta(respuesta.getStatusCode().value(), "Solicitud inválida o ruta inexistente.");
        }
        // Un error JDBC puede incorporar valores de la fila en su detalle.
        LoggerFactory.getLogger(getClass()).error("Error al procesar una solicitud ({})", error.getClass().getSimpleName());
        return respuesta(500, "Ocurrió un error interno.");
    }

    private static ResponseEntity<Map<String, String>> respuesta(int estado, String mensaje) {
        return ResponseEntity.status(estado).body(Map.of("error", mensaje));
    }
}
