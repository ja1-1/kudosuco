package kudos.dominio.valor;

import static kudos.dominio.ReglaException.exigir;

public record Estrellas(int valor) {
    public Estrellas {
        exigir(valor >= 1 && valor <= 5, "La calificación debe ser de 1 a 5.");
    }

    public static Estrellas validar(Object valor) {
        exigir(valor instanceof Integer || valor instanceof Long, "La calificación debe ser de 1 a 5.");
        long entero = ((Number) valor).longValue();
        exigir(entero >= 1 && entero <= 5, "La calificación debe ser de 1 a 5.");
        return new Estrellas((int) entero);
    }
}
