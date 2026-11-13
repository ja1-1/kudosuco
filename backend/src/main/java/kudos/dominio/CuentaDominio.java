package kudos.dominio;

import java.util.Set;
import static kudos.dominio.ReglaException.exigir;

public record CuentaDominio(String estado, boolean correoVerificado, boolean celularVerificado, boolean aprobada) {
    public String aprobar() {
        exigir(estado.equals("PENDING"), "La solicitud ya fue decidida.", 409);
        return "APPROVED";
    }

    public String activar() {
        exigir(estado.equals("APPROVED"), "La cuenta debe estar aprobada.", 409);
        exigir(aprobada && correoVerificado && celularVerificado,
                "Verifica ambos canales y completa la aprobación.", 409);
        return "ACTIVE";
    }

    public String cambiarEstado(String accion) {
        var anteriores = switch (accion) {
            case "suspend" -> Set.of("ACTIVE", "APPROVED");
            case "restore" -> Set.of("SUSPENDED");
            case "deactivate" -> Set.of("PENDING", "APPROVED", "ACTIVE", "SUSPENDED");
            default -> throw new ReglaException(422, "Acción de cuenta inválida.");
        };
        exigir(anteriores.contains(estado), "Transición de cuenta inválida.", 409);
        return switch (accion) {
            case "suspend" -> "SUSPENDED";
            case "restore" -> "APPROVED";
            default -> "DEACTIVATED";
        };
    }
}
