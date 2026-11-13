package kudos.dto;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import kudos.dominio.ReglaException;

import static kudos.dominio.ReglaException.exigir;

public record Solicitud(String token, String remoto, Map<String, Object> datos, Map<String, Object> filtros) {
    public static final String COOKIE = "kudos_session";
    private static final int LIMITE = 16 * 1024 * 1024;
    private static final Set<String> CURSORES = Set.of("before", "after", "sent", "received", "category");
    private static final TypeReference<Map<String, Object>> CUERPO = new TypeReference<>() {};
    private static final ObjectReader PUBLICACION = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
            .build()).build().readerFor(CUERPO);

    public static Solicitud leer(HttpServletRequest http, JsonMapper json) {
        var solicitud = metadatos(http);
        boolean lectura = Set.of("GET", "HEAD").contains(http.getMethod());
        return lectura ? solicitud : solicitud.conDatos(leerDatos(http, json.readerFor(CUERPO), true));
    }

    public static Solicitud metadatos(HttpServletRequest http) {
        var cookies = http.getCookies();
        String token = cookies == null ? "" : Arrays.stream(cookies).filter(cookie -> cookie.getName().equals(COOKIE))
                .map(cookie -> cookie.getValue()).findFirst().orElse("");
        Map<String, Object> filtros = new HashMap<>();
        int campos = 0;
        for (var parametro : http.getParameterMap().entrySet()) {
            var valores = parametro.getValue();
            campos += valores.length;
            exigir(campos <= 12, "Consulta inválida.", 400);
            String clave = parametro.getKey();
            String valor = valores[valores.length - 1];
            if (CURSORES.contains(clave)) {
                if (valor.isEmpty()) continue;
                exigir(valor.matches("[0-9]{1,19}"), "Filtro inválido.", 400);
                try {
                    long id = Long.parseLong(valor);
                    exigir(id > 0, "Filtro inválido.", 400);
                    filtros.put(clave, id);
                } catch (NumberFormatException error) {
                    throw new ReglaException(400, "Filtro inválido.");
                }
            } else {
                filtros.put(clave, valor);
            }
        }
        Object cliente = http.getAttribute("kudos.client-ip");
        return new Solicitud(token, cliente instanceof String ip ? ip : http.getRemoteAddr(), Map.of(), filtros);
    }

    public Solicitud leerPublicacion(HttpServletRequest http) {
        return conDatos(leerDatos(http, PUBLICACION, false));
    }

    private Solicitud conDatos(Map<String, Object> cuerpo) {
        return new Solicitud(token, remoto, cuerpo, filtros);
    }

    private static Map<String, Object> leerDatos(HttpServletRequest http, ObjectReader lector, boolean acotada) {
        String tipo = http.getContentType();
        exigir(tipo != null && tipo.split(";", 2)[0].strip().toLowerCase(Locale.ROOT).equals("application/json"),
                "Se requiere application/json.", 415);
        try {
            Map<String, Object> datos;
            if (acotada) {
                long longitud = http.getContentLengthLong();
                exigir(longitud >= 0, "Se requiere longitud del cuerpo.", 411);
                exigir(longitud <= LIMITE, "La solicitud supera 16 MiB.", 413);
                byte[] cuerpo = http.getInputStream().readNBytes(LIMITE + 1);
                exigir(cuerpo.length <= LIMITE, "La solicitud supera 16 MiB.", 413);
                datos = lector.readValue(cuerpo);
            } else {
                datos = lector.readValue(http.getInputStream());
            }
            exigir(datos != null, "El JSON debe ser un objeto.", 400);
            return datos;
        } catch (IOException | JacksonException error) {
            throw new ReglaException(400, "JSON inválido.");
        }
    }
}
