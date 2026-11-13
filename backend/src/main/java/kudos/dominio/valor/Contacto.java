package kudos.dominio.valor;

import java.util.Locale;
import static kudos.dominio.ReglasKudos.texto;
import static kudos.dominio.ReglaException.exigir;

public record Contacto(String correo, String celular) {
    public static Contacto validar(Object email, Object telefono, String institucion) {
        String correo = texto(email, "Correo", 254).strip().toLowerCase(Locale.ROOT);
        String celular = texto(telefono, "Celular", 32).strip();
        int separador = correo.indexOf('@');
        exigir(separador > 0 && correo.substring(separador + 1).equals(institucion)
                        && correo.substring(0, separador).matches("[a-z0-9][a-z0-9._+%\\-]*"),
                "Se requiere un correo institucional @" + institucion + ".");
        exigir(celular.matches("\\+[1-9][0-9]{7,14}"), "Usa formato internacional para el celular.");
        return new Contacto(correo, celular);
    }
}
