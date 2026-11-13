package kudos.dominio;

import static kudos.dominio.ReglaException.exigir;

public record ReporteDominio(String estado) {
    public String iniciar() {
        exigir(estado.equals("PENDING"), "El reporte ya fue tomado o decidido.", 409);
        return "IN_REVIEW";
    }

    public String decidir(boolean aceptar) {
        exigir(estado.equals("IN_REVIEW"), "Primero inicia la revisión.", 409);
        return aceptar ? "ACCEPTED" : "REJECTED";
    }
}
