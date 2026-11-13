package kudos.dominio;

import java.util.Set;
import kudos.dominio.valor.Estrellas;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReglasKudosTest {
    @Test
    void activaSoloConAprobacionYAmbosCanales() {
        assertThrows(ReglaException.class, () -> new CuentaDominio("PENDING", true, true, true).activar());
        assertThrows(ReglaException.class, () -> new CuentaDominio("APPROVED", false, true, true).activar());
        assertThrows(ReglaException.class, () -> new CuentaDominio("APPROVED", true, false, true).activar());
        assertThrows(ReglaException.class, () -> new CuentaDominio("APPROVED", true, true, false).activar());
        assertEquals("ACTIVE", new CuentaDominio("APPROVED", true, true, true).activar());
    }

    @Test
    void protegeEstadosYValores() {
        assertThrows(ReglaException.class, () -> Estrellas.validar(5.0));
        assertThrows(ReglaException.class, () -> Estrellas.validar(6));
        assertEquals(5, Estrellas.validar(5).valor());
        assertThrows(ReglaException.class, () -> new CategoriaDominio("Colaboración", "", false).instantanea());
        assertThrows(ReglaException.class, () -> new ContenidoDominio("VETOED").interactuar());
        assertEquals("REPORTED", new ContenidoDominio("IN_REVIEW").liberar(Set.of("PENDING")));
        assertEquals("VISIBLE", new ContenidoDominio("REPORTED").liberar(Set.of()));
        assertThrows(ReglaException.class, () -> new ReporteDominio("PENDING").decidir(true));
    }
}
