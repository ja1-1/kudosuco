package kudos.dominio;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

import static kudos.dominio.ReglaException.exigir;

public final class ReglasKudos {
    public static final Set<String> ROLES = Set.of("ADMIN", "RECTOR", "ACADEMIC", "FACULTY", "PROGRAM", "TEACHER", "STUDENT");
    public static final Set<String> CARGOS = Set.of("RECTOR", "ACADEMIC", "FACULTY", "PROGRAM");
    public static final Set<String> MIEMBROS = Set.of("STUDENT", "TEACHER");
    public static final Set<String> MODERADORES = Set.of("RECTOR", "ACADEMIC");
    public static final Set<String> ADMIN_CATEGORIAS = Set.of("ADMIN", "RECTOR", "ACADEMIC");
    public static final Map<String, Set<String>> AUTORIDAD = Map.of(
            "ADMIN", Set.of("ADMIN", "RECTOR"), "RECTOR", Set.of("ACADEMIC"),
            "ACADEMIC", Set.of("FACULTY"), "FACULTY", Set.of("PROGRAM"), "PROGRAM", MIEMBROS);

    private ReglasKudos() {}

    public static String texto(Object valor, String nombre) {
        return texto(valor, nombre, Integer.MAX_VALUE, false);
    }

    public static String texto(Object valor, String nombre, int maximo) {
        return texto(valor, nombre, maximo, false);
    }

    public static String texto(Object valor, String nombre, int maximo, boolean permitirVacio) {
        exigir(valor instanceof String, nombre + " debe ser texto.");
        String texto = (String) valor;
        exigir(texto.codePoints().noneMatch(letra -> letra == 0 || (letra >= 0xd800 && letra <= 0xdfff)),
                nombre + " contiene caracteres inválidos.");
        exigir(permitirVacio || !texto.isBlank(), nombre + " es obligatorio.");
        exigir(texto.codePointCount(0, texto.length()) <= maximo, nombre + " supera el tamaño permitido.");
        return texto;
    }

    public static long identificador(Object valor, String nombre) {
        boolean entero = valor instanceof Integer || valor instanceof Long
                || valor instanceof BigInteger grande && grande.bitLength() <= 63;
        exigir(entero && ((Number) valor).longValue() > 0, nombre + " debe ser un identificador positivo.");
        return ((Number) valor).longValue();
    }

    public static boolean bandera(Object valor) {
        return Boolean.TRUE.equals(valor) || valor instanceof Number numero && numero.intValue() == 1;
    }

    public static boolean booleano(Object valor, String mensaje) {
        exigir(valor instanceof Boolean, mensaje);
        return (Boolean) valor;
    }

    public static String opcion(Object valor, Set<String> opciones, String mensaje) {
        exigir(valor instanceof String opcion && opciones.contains(opcion), mensaje);
        return (String) valor;
    }

    public static void rol(Map<String, Object> actor, Set<String> roles) {
        exigir(actor != null && roles.contains(actor.get("role")), "Operación fuera de tu rol.", 403);
    }

}
