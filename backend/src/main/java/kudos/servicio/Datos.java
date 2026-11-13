package kudos.servicio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Datos {
    private Datos() {}

    public static Map<String, Object> datos(Object... pares) {
        Map<String, Object> resultado = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            resultado.put((String) pares[i], pares[i + 1]);
        }
        return resultado;
    }

    public static Map<String, Object> pagina(List<Map<String, Object>> filas, int limite, String clave) {
        return datos("items", filas.subList(0, Math.min(filas.size(), limite)),
                "next", filas.size() > limite ? filas.get(limite - 1).get(clave) : null);
    }
}
