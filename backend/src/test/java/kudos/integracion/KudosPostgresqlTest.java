package kudos.integracion;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import kudos.servicio.DemostracionServicio;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KUDOS_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "kudos.demo=1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KudosPostgresqlTest {
    private static final String ESQUEMA = "kudos_prueba_" + UUID.randomUUID().toString().replace("-", "");
    private static final String CLAVE_DEMO = "1234";
    private static final String CLAVE_REGISTRO = "Kudos-Prueba-2026!";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int puerto;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemostracionServicio demo;

    @DynamicPropertySource
    static void baseTemporal(DynamicPropertyRegistry propiedades) {
        String url = System.getenv("KUDOS_TEST_DATABASE_URL");
        propiedades.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + ESQUEMA);
        propiedades.add("spring.flyway.schemas", () -> ESQUEMA);
    }

    @BeforeAll
    void cargar() {
        assertTrue(demo.preparar());
        assertFalse(demo.preparar());
    }

    @AfterAll
    void limpiar() {
        jdbc.execute("DROP SCHEMA " + ESQUEMA + " CASCADE");
        http.close();
    }

    @Test
    void migracionYLimitesHttp() throws Exception {
        assertEquals(16, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name<>'flyway_schema_history'
                """, Integer.class));
        assertEquals(List.of("1", "2"), jdbc.queryForList("""
                SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank
                """, String.class));
        assertTrue(llamar(200, "GET", "/health", "", Map.of()).json().get("ok").asBoolean());
        llamar(401, "GET", "/me", "", Map.of());
        llamar(401, "POST", "/kudos", "", Map.of());
        llamar(400, "GET", "/people?after=0", "", Map.of());
        llamar(404, "GET", "/desconocida", "", Map.of());
        var externa = http.send(HttpRequest.newBuilder(uri("/login")).header("Origin", "https://otro.example")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, externa.statusCode());
        var formulario = http.send(HttpRequest.newBuilder(uri("/login")).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(415, formulario.statusCode());
    }

    @Test
    void registroAprobacionYVerificacionDeAmbosCanales() throws Exception {
        long programa = jdbc.queryForObject("SELECT id FROM programs WHERE name='Ingeniería de Sistemas'", Long.class);
        var alta = Map.of("role", "STUDENT", "name", "Estudiante de prueba", "email", "prueba-integracion@uco.edu.co",
                "phone", "+573001111111", "password", CLAVE_REGISTRO, "program_id", programa);
        var claveCorta = new java.util.HashMap<>(alta);
        claveCorta.put("password", CLAVE_DEMO);
        llamar(422, "POST", "/register", "", claveCorta);
        long id = llamar(201, "POST", "/register", "", alta).json().get("id").asLong();
        llamar(409, "POST", "/register", "", alta);
        String persona = llamar(200, "POST", "/login", "", Map.of(
                "email", "prueba-integracion@uco.edu.co", "password", CLAVE_REGISTRO)).cookie();
        String coordinacion = ingreso("programa");
        llamar(200, "GET", "/me/activity", persona, Map.of());
        llamar(403, "GET", "/audit", persona, Map.of());
        llamar(403, "GET", "/kudos", persona, Map.of());
        llamar(403, "POST", "/kudos", persona, Map.of());
        llamar(403, "POST", "/accounts/" + id + "/approve", ingreso("docente"), Map.of());
        llamar(200, "POST", "/accounts/" + id + "/approve", coordinacion, Map.of());
        llamar(409, "POST", "/me/activate", persona, Map.of());
        for (String canal : List.of("email", "phone")) {
            String ruta = "/me/verification/" + canal;
            llamar(200, "POST", ruta, persona, Map.of());
            llamar(429, "POST", ruta, persona, Map.of());
            String codigo = jdbc.queryForObject("SELECT code FROM notifications WHERE account_id=? AND channel=?",
                    String.class, id, canal);
            if (canal.equals("email")) {
                String incorrecto = codigo.equals("00000000") ? "00000001" : "00000000";
                for (int intento = 0; intento < 2; intento++) llamar(422, "PUT", ruta, persona, Map.of("code", incorrecto));
                assertEquals(2, jdbc.queryForObject("SELECT attempts FROM verification WHERE account_id=? AND channel=?",
                        Integer.class, id, canal));
            }
            llamar(200, "PUT", ruta, persona, Map.of("code", codigo));
        }
        assertEquals("ACTIVE", llamar(200, "POST", "/me/activate", persona, Map.of()).json().get("status").asString());
        llamar(200, "GET", "/kudos", persona, Map.of());
        llamar(200, "POST", "/logout", persona, Map.of());
        llamar(401, "GET", "/me", persona, Map.of());
    }

    @Test
    void relevoAtomicoYVinculosAcademicos() throws Exception {
        String admin = ingreso("admin");
        String rector = ingreso("rector");
        String otraSesion = ingreso("rector");
        var contacto = llamar(200, "PATCH", "/me", rector, Map.of("email", "rector-contacto@uco.edu.co", "current_password", CLAVE_DEMO))
                .json();
        long saliente = contacto.get("id").asLong();
        assertEquals("APPROVED", contacto.get("status").asString());
        assertEquals(0, contacto.get("email_verified").asInt());
        llamar(401, "GET", "/me", otraSesion, Map.of());
        llamar(200, "POST", "/me/verification/email", rector, Map.of());
        String codigo = jdbc.queryForObject("SELECT code FROM notifications WHERE account_id=? AND channel='email'",
                String.class, saliente);
        llamar(200, "PUT", "/me/verification/email", rector, Map.of("code", codigo));
        llamar(200, "POST", "/me/activate", rector, Map.of());
        long rectora = llamar(201, "POST", "/accounts", admin, Map.of("role", "RECTOR", "name", "Rectora de prueba",
                "email", "rectora-prueba@uco.edu.co", "phone", "+573002222222", "password", CLAVE_REGISTRO)).json().get("id").asLong();
        jdbc.update("UPDATE accounts SET email_verified=1,phone_verified=1 WHERE id=?", rectora);
        String ruta = "/accounts/" + rectora + "/activate";
        llamar(409, "POST", ruta, admin, Map.of("replaces", rectora));
        llamar(200, "POST", ruta, admin, Map.of("replaces", saliente));
        llamar(401, "GET", "/me", rector, Map.of());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM assignments WHERE account_id=? AND ended_at IS NOT NULL",
                Integer.class, saliente));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE role='RECTOR' AND status='ACTIVE'", Integer.class));
        String coordinacion = ingreso("programa");
        long programa = jdbc.queryForObject("SELECT id FROM programs WHERE name='Ingeniería de Sistemas'", Long.class);
        long docente = jdbc.queryForObject("SELECT id FROM accounts WHERE role='TEACHER'", Long.class);
        String vinculo = "/accounts/" + docente + "/memberships/" + programa;
        llamar(409, "PUT", vinculo, coordinacion, Map.of("active", true));
        llamar(200, "PUT", vinculo, coordinacion, Map.of("active", false));
        llamar(403, "PUT", vinculo, coordinacion, Map.of("active", true));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE account_id=? AND program_id=? AND ended_at IS NOT NULL",
                Integer.class, docente, programa));
    }

    @Test
    void registroMultiprogramaRespetaElAmbitoDeCoordinacion() throws Exception {
        String coordinacion = ingreso("programa");
        long propio = jdbc.queryForObject("SELECT id FROM programs WHERE name='Ingeniería de Sistemas'", Long.class);
        long otro = jdbc.queryForObject("SELECT id FROM programs WHERE name='Licenciatura en Educación'", Long.class);
        long externo = llamar(201, "POST", "/register", "", Map.of("name", "Estudiante de otro programa",
                "email", "fuera-de-ambito@uco.edu.co", "phone", "+573024001111", "password", CLAVE_REGISTRO,
                "role", "STUDENT", "program_id", otro)).json().get("id").asLong();
        String ruta = "/accounts/" + externo;
        llamar(403, "PUT", ruta + "/memberships/" + propio, coordinacion, Map.of("active", true));
        llamar(403, "POST", ruta + "/approve", coordinacion, Map.of());
        assertTrue(llamar(200, "GET", "/accounts", coordinacion, Map.of()).json().valueStream()
                .noneMatch(cuenta -> cuenta.get("id").asLong() == externo));

        long miembro = llamar(201, "POST", "/register", "", Map.of("name", "Estudiante de dos programas",
                "email", "dos-programas@uco.edu.co", "phone", "+573024002222", "password", CLAVE_REGISTRO,
                "role", "STUDENT", "program_ids", List.of(propio, otro))).json().get("id").asLong();
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE account_id=? AND ended_at IS NULL",
                Integer.class, miembro));
        llamar(200, "POST", "/accounts/" + miembro + "/approve", coordinacion, Map.of());
        llamar(403, "PUT", "/accounts/" + miembro + "/memberships/" + otro, coordinacion, Map.of("active", false));
    }

    @Test
    void consultaCoordinadoresResponsables() throws Exception {
        String estudiante = ingreso("estudiante");
        var organizacion = llamar(200, "GET", "/organization", estudiante, Map.of()).json();
        assertEquals("Tomás Montoya", organizacion.get("faculties").valueStream()
                .filter(fila -> fila.get("name").asString().equals("Facultad de Ingenierías"))
                .findFirst().orElseThrow().get("coordinator_name").asString());
        assertEquals("Sara Gil", organizacion.get("programs").valueStream()
                .filter(fila -> fila.get("name").asString().equals("Ingeniería de Sistemas"))
                .findFirst().orElseThrow().get("coordinator_name").asString());
        assertFalse(llamar(200, "GET", "/options", "", Map.of()).json().get("faculties").get(0).has("coordinator_name"));
    }

    @Test
    void publicacionConservaTextoYCategoriaHistorica() throws Exception {
        String estudiante = ingreso("estudiante");
        String admin = ingreso("admin");
        long categoria = llamar(201, "POST", "/categories", admin, Map.of("name", "Categoría histórica", "description", "Original"))
                .json().get("id").asLong();
        long persona = jdbc.queryForObject("SELECT id FROM accounts WHERE role='TEACHER'", Long.class);
        String texto = "Aporte institucional. ".repeat(5_500);
        long id = llamar(201, "POST", "/kudos", estudiante, Map.of("category_id", categoria, "recipient_id", persona, "body", texto))
                .json().get("id").asLong();
        llamar(200, "PATCH", "/categories/" + categoria, admin, Map.of("name", "Nueva", "active", false));
        llamar(409, "POST", "/kudos", estudiante, Map.of("category_id", categoria, "recipient_id", persona, "body", "Otro aporte"));
        var detalle = llamar(200, "GET", "/kudos/" + id, estudiante, Map.of()).json().get("kudo");
        assertEquals(texto, detalle.get("body").asString());
        assertEquals("Categoría histórica", detalle.get("category_name").asString());
    }

    @Test
    void guardaYRecuperaKudosYReplicasDeMasDeVeinteMiB() throws Exception {
        String estudiante = ingreso("estudiante");
        long categoria = jdbc.queryForObject("SELECT id FROM categories WHERE name='Colaboración'", Long.class);
        long persona = jdbc.queryForObject("SELECT id FROM accounts WHERE role='TEACHER'", Long.class);
        String texto = "a".repeat(21 * 1024 * 1024) + " fin del texto ñ";
        long id = llamar(201, "POST", "/kudos", estudiante,
                Map.of("category_id", categoria, "recipient_id", persona, "body", texto)).json().get("id").asLong();
        try {
            byte[] replica = json.writeValueAsBytes(Map.of("body", texto));
            var respuesta = http.send(HttpRequest.newBuilder(uri("/content/" + id + "/replies"))
                    .timeout(Duration.ofSeconds(30)).header("Cookie", estudiante).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(replica))).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(201, respuesta.statusCode(), respuesta.body());

            var detalle = http.send(HttpRequest.newBuilder(uri("/kudos/" + id)).timeout(Duration.ofSeconds(30))
                    .header("Cookie", estudiante).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var cuerpo = detalle.body()) {
                assertEquals(200, detalle.statusCode());
                var lector = JsonMapper.builder(JsonFactory.builder()
                        .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
                        .build()).build();
                var resultado = lector.readTree(cuerpo);
                assertTrue(texto.equals(resultado.get("kudo").get("body").asString()), "El texto del Kudo cambió");
                assertTrue(texto.equals(resultado.get("replies").get(0).get("body").asString()), "El texto de la réplica cambió");
            }
        } finally {
            jdbc.update("DELETE FROM content WHERE root_id=?", id);
            jdbc.update("DELETE FROM content WHERE id=?", id);
        }
    }

    @Test
    void conversacionProfundaYPadreInmutable() throws Exception {
        String estudiante = ingreso("estudiante");
        long id = publicar(estudiante, "Conversación profunda");
        long otro = publicar(estudiante, "Conversación distinta");
        long autor = jdbc.queryForObject("SELECT id FROM accounts WHERE email='estudiante@uco.edu.co'", Long.class);
        jdbc.execute("""
                DO $$
                DECLARE padre bigint := %d;
                BEGIN
                    FOR i IN 1..1050 LOOP
                        INSERT INTO content(kind,author_id,root_id,parent_id,body,created_at,state)
                        VALUES ('REPLY',%d,%d,padre,'Réplica de integración','2026-11-12T00:00:00Z','VISIBLE')
                        RETURNING id INTO padre;
                    END LOOP;
                END $$;
                """.formatted(id, autor, id));
        assertEquals(1050, llamar(200, "GET", "/kudos/" + id, estudiante, Map.of()).json().get("replies").size());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE content SET parent_id=? WHERE root_id=?", otro, id));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO content(kind,author_id,root_id,parent_id,body,created_at,state)
                VALUES ('REPLY',?,?,?,'Inválida','2026-11-12T00:00:00Z','VISIBLE')
                """, autor, id, otro));
    }

    @Test
    void consultasDeGestionRegistranActorSinCopiarDatosPersonales() throws Exception {
        for (String alias : List.of("rector", "programa")) {
            String sesion = ingreso(alias);
            long actor = jdbc.queryForObject("SELECT id FROM accounts WHERE email=?", Long.class, alias + "@uco.edu.co");
            long corte = jdbc.queryForObject("SELECT coalesce(max(id),0) FROM audit", Long.class);
            var cuentas = llamar(200, "GET", "/accounts", sesion, Map.of()).json();
            llamar(200, "GET", "/organization", sesion, Map.of());
            assertEquals(List.of("CuentasConsultadas", "OrganizacionConsultada"), jdbc.queryForList("""
                    SELECT event FROM audit WHERE actor_id=? AND id>? AND event IN ('CuentasConsultadas','OrganizacionConsultada')
                    ORDER BY id
                    """, String.class, actor, corte));
            assertEquals(cuentas.size(), jdbc.queryForObject("""
                    SELECT (facts::jsonb->>'count')::int FROM audit WHERE actor_id=? AND id>? AND event='CuentasConsultadas'
                    """, Integer.class, actor, corte));
        }
    }

    @Test
    void consultasDeModeracionConservanPermisosEHistorial() throws Exception {
        String estudiante = ingreso("estudiante");
        String administrador = ingreso("admin");
        String rector = ingreso("rector");
        long kudo = publicar(estudiante, "Consulta de moderación");
        long reporte = llamar(201, "POST", "/content/" + kudo + "/reports", estudiante, Map.of("reason", "SPAM"))
                .json().get("id").asLong();

        llamar(403, "GET", "/moderation/reports", estudiante, Map.of());
        llamar(403, "GET", "/moderation/vetos", administrador, Map.of());
        var reportes = llamar(200, "GET", "/moderation/reports", rector, Map.of()).json();
        assertTrue(reportes.get("items").valueStream().anyMatch(fila -> fila.get("id").asLong() == reporte));
        var anteriores = llamar(200, "GET", "/moderation/reports?before=" + reporte, rector, Map.of()).json();
        assertTrue(anteriores.get("items").valueStream().allMatch(fila -> fila.get("id").asLong() < reporte));

        String revision = "/moderation/reports/" + reporte;
        llamar(200, "POST", revision, rector, Map.of("action", "start"));
        llamar(200, "POST", revision, rector, Map.of("action", "accept", "reason", "Veto de prueba"));
        var vetos = llamar(200, "GET", "/moderation/vetos", rector, Map.of()).json();
        assertTrue(vetos.get("items").valueStream().anyMatch(fila ->
                fila.get("content_id").asLong() == kudo && fila.get("report_id").asLong() == reporte));

        var auditoria = llamar(200, "GET", "/audit", rector, Map.of()).json();
        assertTrue(auditoria.get("items").valueStream().anyMatch(fila ->
                fila.get("event").asString().equals("KudoVetado") && fila.get("subject_id").asLong() == kudo
                        && fila.get("facts").get("report_id").asLong() == reporte));
        var institucional = llamar(200, "GET", "/audit", administrador, Map.of()).json();
        assertTrue(institucional.get("items").valueStream().noneMatch(fila ->
                List.of("content", "report").contains(fila.get("subject").asString())));
        var personal = llamar(200, "GET", "/me/activity", estudiante, Map.of()).json();
        long actor = llamar(200, "GET", "/me", estudiante, Map.of()).json().get("id").asLong();
        assertTrue(personal.get("items").valueStream().allMatch(fila -> fila.get("actor_id").asLong() == actor));
        assertTrue(personal.get("items").valueStream().anyMatch(fila ->
                fila.get("event").asString().equals("KudoReportado") && fila.get("subject_id").asLong() == kudo));
    }

    @Test
    void conflictoAlGuardarVetoRevierteDecisionEstadoYAuditoria() throws Exception {
        String estudiante = ingreso("estudiante");
        String rector = ingreso("rector");
        long kudo = publicar(estudiante, "Comprobación de rollback");
        long reporte = llamar(201, "POST", "/content/" + kudo + "/reports", estudiante, Map.of("reason", "SPAM"))
                .json().get("id").asLong();
        String revision = "/moderation/reports/" + reporte;
        llamar(200, "POST", revision, rector, Map.of("action", "start"));
        // Fuerza una colisión en la PK de vetos después de actualizar reporte, contenido y auditoría.
        jdbc.update("""
                INSERT INTO vetos(content_id,moderator_id,moderator_role,reason,created_at)
                SELECT ?,id,role,'Colisión de prueba','2026-11-13T00:00:00Z' FROM accounts WHERE email='rector@uco.edu.co'
                """, kudo);
        var decision = Map.of("action", "accept", "reason", "Confirmación de prueba");
        llamar(409, "POST", revision, rector, decision);
        assertEquals("IN_REVIEW", jdbc.queryForObject("SELECT state FROM reports WHERE id=?", String.class, reporte));
        assertEquals("IN_REVIEW", jdbc.queryForObject("SELECT state FROM content WHERE id=?", String.class, kudo));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM audit WHERE event='RevisionCerrada' AND subject='report' AND subject_id=?
                """, Integer.class, reporte));

        jdbc.update("DELETE FROM vetos WHERE content_id=?", kudo);
        var resultado = llamar(200, "POST", revision, rector, decision).json();
        assertEquals("ACCEPTED", resultado.get("state").asString());
        assertEquals("VETOED", resultado.get("content_state").asString());
        assertEquals(reporte, jdbc.queryForObject("SELECT report_id FROM vetos WHERE content_id=?", Long.class, kudo));
    }

    @Test
    void calificacionConcurrenteReporteVetoYAuditoria() throws Exception {
        String estudiante = ingreso("estudiante");
        String docente = ingreso("docente");
        String moderador = ingreso("academico");
        long id = publicar(estudiante, "Conversación moderada");
        long rama = llamar(201, "POST", "/content/" + id + "/replies", docente, Map.of("body", "Réplica"))
                .json().get("id").asLong();
        long hija = llamar(201, "POST", "/content/" + rama + "/replies", estudiante, Map.of("body", "Descendiente"))
                .json().get("id").asLong();
        try (var concurrentes = Executors.newVirtualThreadPerTaskExecutor()) {
            var primera = concurrentes.submit(() -> llamar(200, "PUT", "/content/" + rama + "/rating", estudiante, Map.of("stars", 3)));
            var segunda = concurrentes.submit(() -> llamar(200, "PUT", "/content/" + rama + "/rating", estudiante, Map.of("stars", 4)));
            primera.get();
            segunda.get();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ratings WHERE content_id=?", Integer.class, rama));
        String consultaValoraciones = "/content/" + rama + "/ratings";
        llamar(401, "GET", consultaValoraciones, "", Map.of());
        llamar(200, "PUT", "/content/" + id + "/rating", docente, Map.of("stars", 5));
        assertEquals(5, llamar(200, "GET", "/content/" + id + "/ratings", estudiante, Map.of())
                .json().get("items").get(0).get("stars").asInt());
        var calificaciones = llamar(200, "GET", consultaValoraciones, docente, Map.of()).json();
        assertEquals(1, calificaciones.get("items").size());
        var calificacion = calificaciones.get("items").get(0);
        assertFalse(calificacion.has("account_id"));
        assertTrue(calificacion.hasNonNull("created_at"));
        assertTrue(calificacion.hasNonNull("updated_at"));
        long reporte = llamar(201, "POST", "/content/" + rama + "/reports", estudiante, Map.of("reason", "SPAM"))
                .json().get("id").asLong();
        long otroReporte = llamar(201, "POST", "/content/" + rama + "/reports", estudiante, Map.of("reason", "OTHER"))
                .json().get("id").asLong();
        llamar(403, "POST", "/content/" + rama + "/veto", docente, Map.of("reason", "Fuera de rol"));
        String ruta = "/moderation/reports/" + reporte;
        llamar(200, "POST", ruta, moderador, Map.of("action", "start"));
        llamar(200, "POST", ruta, moderador, Map.of("action", "accept", "reason", "Justificación de prueba"));
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM reports WHERE id=?", String.class, otroReporte));
        String otraRuta = "/moderation/reports/" + otroReporte;
        llamar(200, "POST", otraRuta, moderador, Map.of("action", "start"));
        var otraDecision = llamar(200, "POST", otraRuta, moderador, Map.of("action", "reject", "reason", "Sin mérito")).json();
        assertEquals("VETOED", otraDecision.get("content_state").asString());
        llamar(404, "GET", consultaValoraciones, estudiante, Map.of());
        llamar(404, "PUT", "/content/" + hija + "/rating", estudiante, Map.of("stars", 1));
        assertEquals(0, llamar(200, "GET", "/kudos/" + id, estudiante, Map.of()).json().get("replies").size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit WHERE event='ReplicaVetada' AND subject_id=?", Integer.class, rama));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM audit WHERE subject_id=?", rama));
    }

    @Test
    void rechazarUltimoReporteLiberaContenidoYVetoDirectoLoOculta() throws Exception {
        String estudiante = ingreso("estudiante");
        String docente = ingreso("docente");
        String moderador = ingreso("academico");
        long id = publicar(estudiante, "Reconocimiento con revisión");
        var primerReporte = llamar(201, "POST", "/content/" + id + "/reports", estudiante, Map.of("reason", "SPAM")).json();
        var segundoReporte = llamar(201, "POST", "/content/" + id + "/reports", docente, Map.of("reason", "SPAM")).json();
        var reportes = List.of(primerReporte.get("id").asLong(), segundoReporte.get("id").asLong());
        var estados = List.of("REPORTED", "VISIBLE");
        for (int indice = 0; indice < reportes.size(); indice++) {
            String ruta = "/moderation/reports/" + reportes.get(indice);
            llamar(200, "POST", ruta, moderador, Map.of("action", "start"));
            var decision = llamar(200, "POST", ruta, moderador, Map.of("action", "reject", "reason", "Sin mérito")).json();
            assertEquals(estados.get(indice), decision.get("content_state").asString());
        }
        llamar(200, "POST", "/content/" + id + "/veto", moderador, Map.of("reason", "Veto directo"));
        llamar(404, "GET", "/kudos/" + id, estudiante, Map.of());
    }

    @Test
    void limitaIntentosFallidosDeIngreso() throws Exception {
        var intento = Map.of("email", "sin-cuenta@uco.edu.co", "password", CLAVE_REGISTRO);
        for (int i = 0; i < 12; i++) llamar(401, "POST", "/login", "", intento);
        llamar(429, "POST", "/login", "", intento);
    }

    private long publicar(String sesion, String texto) throws Exception {
        long persona = jdbc.queryForObject("SELECT id FROM accounts WHERE role='TEACHER'", Long.class);
        long categoria = jdbc.queryForObject("SELECT id FROM categories WHERE name='Colaboración'", Long.class);
        return llamar(201, "POST", "/kudos", sesion, Map.of("category_id", categoria, "recipient_id", persona, "body", texto))
                .json().get("id").asLong();
    }

    private String ingreso(String alias) throws Exception {
        return llamar(200, "POST", "/login", "", Map.of("email", alias + "@uco.edu.co", "password", CLAVE_DEMO)).cookie();
    }

    private URI uri(String ruta) {
        return URI.create("http://127.0.0.1:" + puerto + "/api" + ruta);
    }

    private Respuesta llamar(int esperado, String metodo, String ruta, String cookie, Map<String, ?> datos) throws Exception {
        var peticion = HttpRequest.newBuilder(uri(ruta)).timeout(Duration.ofSeconds(12));
        if (!cookie.isEmpty()) peticion.header("Cookie", cookie);
        if (metodo.equals("GET")) {
            peticion.GET();
        } else {
            peticion.header("Content-Type", "application/json")
                    .method(metodo, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(datos)));
        }
        var respuesta = http.send(peticion.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(esperado, respuesta.statusCode(), () -> ruta + " " + respuesta.body());
        return new Respuesta(json.readTree(respuesta.body()), respuesta.headers().firstValue("Set-Cookie").orElse("").split(";", 2)[0]);
    }

    private record Respuesta(JsonNode json, String cookie) {}
}
