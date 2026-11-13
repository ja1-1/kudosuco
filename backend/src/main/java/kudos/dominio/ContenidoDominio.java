package kudos.dominio;

import java.util.Set;
import static kudos.dominio.ReglaException.exigir;

public record ContenidoDominio(String estado) {
    public String publicar() {
        exigir(estado.equals("CREATED"), "El contenido ya fue publicado.", 409);
        return "VISIBLE";
    }

    public void interactuar() {
        exigir(Set.of("VISIBLE", "REPORTED", "IN_REVIEW").contains(estado), "ContenidoDominio no disponible.", 404);
    }

    public String reportar() {
        interactuar();
        return estado.equals("VISIBLE") ? "REPORTED" : estado;
    }

    public String revisar() {
        interactuar();
        exigir(!estado.equals("VISIBLE"), "El contenido necesita un reporte.", 409);
        return "IN_REVIEW";
    }

    public String liberar(Set<String> abiertos) {
        interactuar();
        if (abiertos.contains("IN_REVIEW")) {
            return "IN_REVIEW";
        }
        return abiertos.contains("PENDING") ? "REPORTED" : "VISIBLE";
    }

    public String vetar() {
        interactuar();
        return "VETOED";
    }
}
