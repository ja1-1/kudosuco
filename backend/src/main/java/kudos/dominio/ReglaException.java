package kudos.dominio;

public class ReglaException extends RuntimeException {
    private final int estado;

    public ReglaException(int estado, String mensaje) {
        super(mensaje);
        this.estado = estado;
    }

    public int estado() {
        return estado;
    }

    public static void exigir(boolean valido, String mensaje) {
        exigir(valido, mensaje, 422);
    }

    public static void exigir(boolean valido, String mensaje, int estado) {
        if (!valido) {
            throw new ReglaException(estado, mensaje);
        }
    }
}
