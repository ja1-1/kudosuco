package kudos.dominio;

import java.util.List;
import static kudos.dominio.ReglaException.exigir;

public record CategoriaDominio(String nombre, String descripcion, boolean activa) {
    public List<String> instantanea() {
        exigir(activa, "La categoría está inactiva.", 409);
        return List.of(nombre, descripcion);
    }
}
