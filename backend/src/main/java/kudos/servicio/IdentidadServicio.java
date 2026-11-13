package kudos.servicio;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

import kudos.configuracion.KudosConfiguracion;
import kudos.dao.datos.postgresql.KudosPostgresqlDAO;
import kudos.dominio.valor.Contacto;
import kudos.dominio.CuentaDominio;

import static kudos.dao.datos.postgresql.KudosPostgresqlDAO.ahora;
import static kudos.dominio.ReglaException.exigir;
import static kudos.dominio.ReglasKudos.*;
import static kudos.servicio.Datos.datos;

@Service
public class IdentidadServicio {
    private final KudosPostgresqlDAO dao;
    private final KudosConfiguracion config;
    private final SecureRandom aleatorio = new SecureRandom();
    private static final Set<String> CANALES = Set.of("email", "phone");
    private static final String HASH_FICTICIO = "0".repeat(32) + ":" + "0".repeat(64);

    public IdentidadServicio(KudosPostgresqlDAO dao, KudosConfiguracion config) {
        this.dao = dao;
        this.config = config;
    }

    public Map<String, Object> cuenta(long id) {
        var fila = dao.uno("SELECT * FROM accounts WHERE id=?", id);
        exigir(fila != null, "Cuenta inexistente.", 404);
        return fila;
    }

    private boolean enAmbito(Map<String, Object> actor, Map<String, Object> cuenta) {
        return switch ((String) actor.get("role")) {
            case "FACULTY" -> dao.uno("SELECT 1 FROM programs WHERE id=? AND faculty_id=?",
                    cuenta.get("program_id"), actor.get("faculty_id")) != null;
            case "PROGRAM" -> dao.uno("""
                    SELECT 1 FROM memberships WHERE account_id=? AND program_id=? AND ended_at IS NULL
                    """, cuenta.get("id"), actor.get("program_id")) != null;
            default -> true;
        };
    }

    private void gestionar(Map<String, Object> actor, Map<String, Object> cuenta, boolean ambito) {
        exigir(!Objects.equals(actor.get("id"), cuenta.get("id")), "No puedes gestionar tu propia solicitud.", 403);
        exigir(ambito && AUTORIDAD.getOrDefault(actor.get("role"), Set.of()).contains(cuenta.get("role")),
                "La cuenta está fuera de tu ámbito de autoridad.", 403);
    }

    public Map<String, Object> registrar(Map<String, Object> datos, Map<String, Object> actor, boolean inicial) {
        String perfil = opcion(datos.get("role"), ROLES, "Rol desconocido.");
        if (actor != null) {
            rol(actor, Set.of("ADMIN"));
            exigir(Set.of("ADMIN", "RECTOR").contains(perfil), "Este rol usa el registro público.", 403);
        } else if (inicial) {
            exigir(perfil.equals("ADMIN") && dao.uno("SELECT id FROM accounts LIMIT 1") == null,
                    "La instalación ya tiene cuentas.", 409);
        } else {
            exigir(!Set.of("ADMIN", "RECTOR").contains(perfil), "Este rol debe ser registrado por un administrador.", 403);
            exigir(bandera(dao.uno("SELECT registration_open FROM settings").get("registration_open")),
                    "El registro está cerrado.", 403);
        }
        var contacto = Contacto.validar(datos.get("email"), datos.get("phone"), config.institution());
        exigir(dao.uno("SELECT id FROM accounts WHERE email=?", contacto.correo()) == null,
                "El correo ya está registrado.", 409);
        exigir(dao.uno("SELECT id FROM accounts WHERE phone=?", contacto.celular()) == null,
                "El celular ya está registrado.", 409);
        String nombre = texto(datos.get("name"), "Nombre", 120).strip();
        Long facultad = perfil.equals("FACULTY") ? identificador(datos.get("faculty_id"), "Facultad") : null;
        Long programa = perfil.equals("PROGRAM") ? identificador(datos.get("program_id"), "Programa") : null;
        var programas = MIEMBROS.contains(perfil) ? programasSolicitados(datos) : List.<Long>of();
        if (facultad != null) {
            exigir(dao.uno("SELECT id FROM programs WHERE faculty_id=? LIMIT 1", facultad) != null,
                    "La facultad debe tener al menos un programa.");
        }
        if (programa != null) {
            exigir(dao.uno("SELECT id FROM programs WHERE id=?", programa) != null, "Programa inexistente.");
        }
        for (long programaSolicitado : programas) {
            exigir(dao.uno("SELECT id FROM programs WHERE id=?", programaSolicitado) != null, "Programa inexistente.");
        }
        String fecha = ahora();
        Object actorId = actor == null ? null : actor.get("id");
        boolean aprobado = actor != null || inicial;
        String estado = aprobado ? "APPROVED" : "PENDING";
        long id = dao.insertar("""
                INSERT INTO accounts(name,email,phone,password_hash,role,faculty_id,program_id,status,approved_by,
                    approved_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, nombre, contacto.correo(), contacto.celular(), nuevaClave(datos.get("password")), perfil, facultad,
                perfil.equals("PROGRAM") ? programa : null, estado, actorId, aprobado ? fecha : null, fecha);
        dao.registrar("UsuarioRegistrado", actorId == null ? id : actorId, "account", id, datos("role", perfil));
        for (long programaSolicitado : programas) {
            vincular(id, programaSolicitado, id, true, perfil);
        }
        if (aprobado) {
            dao.registrar("UsuarioAprobado", actorId, "account", id, datos("role", perfil));
        }
        return datos("id", id, "status", estado);
    }

    private static List<Long> programasSolicitados(Map<String, Object> datos) {
        if (!datos.containsKey("program_ids")) {
            return List.of(identificador(datos.get("program_id"), "Programa"));
        }
        Object solicitados = datos.get("program_ids");
        exigir(solicitados instanceof List<?> lista && !lista.isEmpty() && !datos.containsKey("program_id"),
                "Selecciona al menos un programa.");
        return ((List<?>) solicitados).stream().map(id -> identificador(id, "Programa")).distinct().toList();
    }

    public record Ingreso(int estado, String error, String token, Map<String, Object> cuenta) {}

    public Ingreso ingresar(Map<String, Object> datos, String remoto) {
        String email = texto(datos.get("email"), "Correo", 254).strip().toLowerCase(Locale.ROOT);
        String clave = texto(datos.get("password"), "Contraseña", 128);
        if (limitado("login:ip:" + digest(remoto), 120, 900) || limitado("login:email:" + digest(email), 12, 900)) {
            return new Ingreso(429, "Demasiados intentos. Intenta más tarde.", "", null);
        }
        var fila = dao.uno("SELECT * FROM accounts WHERE email=?", email);
        boolean valida = coincideClave(clave, fila == null ? HASH_FICTICIO : (String) fila.get("password_hash"));
        if (fila == null || !valida || Set.of("SUSPENDED", "DEACTIVATED").contains(fila.get("status"))) {
            return new Ingreso(401, "Credenciales inválidas o cuenta bloqueada.", "", null);
        }
        dao.ejecutar("DELETE FROM rate_limits WHERE bucket=?", "login:email:" + digest(email));
        long momento = epoch();
        dao.ejecutar("DELETE FROM sessions WHERE expires<=?", momento);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(32));
        dao.ejecutar("INSERT INTO sessions VALUES (?,?,?)", digest(token), fila.get("id"), momento + 28800);
        return new Ingreso(200, null, token, publica(fila));
    }

    public Map<String, Object> sesion(String token) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            return null;
        }
        return dao.uno("""
                SELECT a.* FROM sessions s JOIN accounts a ON a.id=s.account_id
                WHERE s.digest=? AND s.expires>? AND a.status NOT IN ('SUSPENDED','DEACTIVATED')
                """, digest(token), epoch());
    }

    public void cerrarSesion(String token) {
        dao.ejecutar("DELETE FROM sessions WHERE digest=?", digest(token));
    }

    public Map<String, Object> aprobar(Map<String, Object> actor, long id) {
        var fila = cuenta(id);
        gestionar(actor, fila, enAmbito(actor, fila));
        dao.ejecutar("UPDATE accounts SET status=?,approved_by=?,approved_at=? WHERE id=?",
                modelo(fila).aprobar(), actor.get("id"), ahora(), id);
        dao.registrar("UsuarioAprobado", actor.get("id"), "account", id, datos("role", fila.get("role")));
        return publica(cuenta(id));
    }

    private Map<String, Object> titular(Map<String, Object> fila) {
        return CARGOS.contains(fila.get("role")) ? dao.uno("""
                SELECT id FROM accounts WHERE role=? AND status='ACTIVE' AND faculty_id IS NOT DISTINCT FROM ?::bigint
                  AND program_id IS NOT DISTINCT FROM ?::bigint AND id<>?
                """, fila.get("role"), fila.get("faculty_id"), fila.get("program_id"), fila.get("id")) : null;
    }

    public Map<String, Object> activar(Map<String, Object> fila, Map<String, Object> actor, Long sustituye) {
        String estado = modelo(fila).activar();
        long id = (long) fila.get("id");
        Object actorId = actor == null ? id : actor.get("id");
        if (actor != null) {
            gestionar(actor, fila, enAmbito(actor, fila));
        }
        var titular = titular(fila);
        if (titular != null) {
            exigir(actor != null && Objects.equals(sustituye, titular.get("id")),
                    "El cargo está ocupado. Solicita un reemplazo autorizado.", 409);
            var saliente = cuenta(sustituye);
            gestionar(actor, saliente, enAmbito(actor, saliente));
            guardarEstado(saliente, "SUSPENDED", actorId, "CuentaSuspendida", datos("reason", "Relevo de cargo"));
        } else {
            exigir(sustituye == null, "La persona a reemplazar ya no ocupa el cargo.", 409);
        }
        dao.ejecutar("UPDATE accounts SET status=? WHERE id=?", estado, id);
        dao.registrar("CuentaActivada", actorId, "account", id, Map.of());
        if (CARGOS.contains(fila.get("role"))) {
            dao.ejecutar("""
                    INSERT INTO assignments(account_id,role,faculty_id,program_id,started_at,appointed_by)
                    VALUES (?,?,?,?,?,?)
                    """, id, fila.get("role"), fila.get("faculty_id"), fila.get("program_id"), ahora(), actorId);
            String cargo = Map.of("FACULTY", "CoordinadorFacultad", "PROGRAM", "CoordinadorPrograma",
                    "ACADEMIC", "CoordinadorAcademico", "RECTOR", "Rector").get(fila.get("role"));
            dao.registrar(cargo + (titular == null ? "Asignado" : "Reemplazado"), actorId, "account", id,
                    datos("replaces", sustituye, "faculty_id", fila.get("faculty_id"), "program_id", fila.get("program_id")));
        }
        return publica(cuenta(id));
    }

    private void guardarEstado(Map<String, Object> fila, String estado, Object actor, String evento,
                               Map<String, Object> hechos) {
        Object id = fila.get("id");
        dao.ejecutar("UPDATE accounts SET status=? WHERE id=?", estado, id);
        dao.ejecutar("UPDATE assignments SET ended_at=?,ended_by=? WHERE account_id=? AND ended_at IS NULL",
                ahora(), actor, id);
        if (Set.of("SUSPENDED", "DEACTIVATED").contains(estado)) {
            dao.ejecutar("DELETE FROM sessions WHERE account_id=?", id);
            dao.ejecutar("DELETE FROM verification WHERE account_id=?", id);
            dao.ejecutar("DELETE FROM notifications WHERE account_id=?", id);
        }
        hechos.put("previous", fila.get("status"));
        hechos.put("status", estado);
        dao.registrar(evento, actor, "account", id, hechos);
    }

    public Map<String, Object> cambiarEstado(Map<String, Object> actor, long id, Map<String, Object> entrada) {
        var fila = cuenta(id);
        gestionar(actor, fila, enAmbito(actor, fila));
        var eventos = Map.of("suspend", "CuentaSuspendida", "restore", "CuentaRestablecida", "deactivate", "CuentaDesactivada");
        String accion = opcion(entrada.get("action"), eventos.keySet(), "Acción de cuenta inválida.");
        String estado = modelo(fila).cambiarEstado(accion);
        guardarEstado(fila, estado, actor.get("id"), eventos.get(accion),
                datos("reason", texto(entrada.get("reason"), "Motivo", 500).strip()));
        return publica(cuenta(id));
    }

    public Map<String, Object> actualizar(Map<String, Object> fila, Map<String, Object> entrada, String token) {
        long id = (long) fila.get("id");
        String nombre = texto(entrada.getOrDefault("name", fila.get("name")), "Nombre", 120).strip();
        var contacto = Contacto.validar(entrada.getOrDefault("email", fila.get("email")),
                entrada.getOrDefault("phone", fila.get("phone")), config.institution());
        List<String> cambios = new ArrayList<>();
        if (!contacto.correo().equals(fila.get("email"))) cambios.add("email");
        if (!contacto.celular().equals(fila.get("phone"))) cambios.add("phone");
        Object claveNueva = entrada.get("new_password");
        String guardada = (String) fila.get("password_hash");
        boolean seguridad = !cambios.isEmpty() || claveNueva != null;
        if (seguridad) {
            exigir(coincideClave(texto(entrada.get("current_password"), "Contraseña actual", 128), guardada),
                    "Contraseña actual incorrecta.", 403);
        }
        boolean renombra = !nombre.equals(fila.get("name"));
        if (!renombra && !seguridad) {
            return publica(fila);
        }
        if (!cambios.isEmpty() && "ACTIVE".equals(fila.get("status"))) {
            guardarEstado(fila, "APPROVED", id, "CuentaPendienteVerificacion", datos());
        }
        for (String canal : cambios) {
            dao.ejecutar("DELETE FROM verification WHERE account_id=? AND channel=?", id, canal);
            dao.ejecutar("DELETE FROM notifications WHERE account_id=? AND channel=?", id, canal);
        }
        dao.ejecutar("""
                UPDATE accounts SET name=?,email=?,phone=?,email_verified=?,phone_verified=?,password_hash=? WHERE id=?
                """, nombre, contacto.correo(), contacto.celular(), cambios.contains("email") ? 0 : fila.get("email_verified"),
                cambios.contains("phone") ? 0 : fila.get("phone_verified"), claveNueva == null ? guardada : nuevaClave(claveNueva), id);
        if (seguridad) {
            dao.ejecutar("DELETE FROM sessions WHERE account_id=? AND digest<>?", id, digest(token));
        }
        dao.registrar("PerfilActualizado", id, "account", id,
                datos("channels", cambios, "name_changed", renombra, "password_changed", claveNueva != null));
        return publica(cuenta(id));
    }

    public Map<String, Object> solicitarCodigo(Map<String, Object> fila, String canal) {
        opcion(canal, CANALES, "Canal desconocido.");
        exigir(!bandera(fila.get(canal + "_verified")), "El canal ya está verificado.", 409);
        long momento = epoch();
        Object id = fila.get("id");
        var previo = dao.uno("SELECT requested FROM verification WHERE account_id=? AND channel=?", id, canal);
        exigir(previo == null || momento - (long) previo.get("requested") >= 60, "Espera un minuto antes de reenviar.", 429);
        String destino = (String) fila.get(canal);
        exigir(!limitado("verification:" + digest(destino), 8, 3600), "Demasiados códigos solicitados. Intenta más tarde.", 429);
        String codigo = String.format(Locale.ROOT, "%08d", aleatorio.nextInt(100_000_000));
        dao.ejecutar("""
                INSERT INTO verification(account_id,channel,digest,requested) VALUES (?,?,?,?)
                ON CONFLICT(account_id,channel) DO UPDATE SET digest=excluded.digest,requested=excluded.requested,attempts=0
                """, id, canal, firma(fila, canal, codigo), momento);
        dao.ejecutar("DELETE FROM notifications WHERE account_id=? AND channel=?", id, canal);
        dao.ejecutar("INSERT INTO notifications(account_id,channel,destination,code,expires) VALUES (?,?,?,?,?)",
                id, canal, destino, codigo, momento + 600);
        return datos("queued", true);
    }

    public boolean verificar(Map<String, Object> fila, String canal, Map<String, Object> entrada) {
        opcion(canal, CANALES, "Canal desconocido.");
        String codigo = texto(entrada.get("code"), "Código", 8);
        Object id = fila.get("id");
        var verificacion = dao.uno("SELECT * FROM verification WHERE account_id=? AND channel=?", id, canal);
        exigir(verificacion != null && epoch() < (long) verificacion.get("requested") + 600, "Solicita un código vigente.", 409);
        exigir((int) verificacion.get("attempts") < 5, "Agotaste los intentos. Solicita otro código.", 429);
        if (!iguales((String) verificacion.get("digest"), firma(fila, canal, codigo))) {
            dao.ejecutar("UPDATE verification SET attempts=attempts+1 WHERE account_id=? AND channel=?", id, canal);
            return false;
        }
        String columna = canal.equals("email") ? "email_verified" : "phone_verified";
        dao.ejecutar("UPDATE accounts SET " + columna + "=1 WHERE id=?", id);
        dao.ejecutar("DELETE FROM verification WHERE account_id=? AND channel=?", id, canal);
        dao.ejecutar("DELETE FROM notifications WHERE account_id=? AND channel=?", id, canal);
        dao.registrar(canal.equals("email") ? "CorreoVerificado" : "CelularVerificado", id, "account", id, Map.of());
        return true;
    }

    public Map<String, Object> cambiarVinculo(Map<String, Object> actor, long id, long programa, boolean activo) {
        var fila = cuenta(id);
        rol(actor, Set.of("PROGRAM"));
        gestionar(actor, fila, Objects.equals(actor.get("program_id"), programa) && enAmbito(actor, fila));
        exigir(!"DEACTIVATED".equals(fila.get("status")), "La cuenta está desactivada.", 409);
        exigir(!activo, "La cuenta ya tiene un vínculo vigente con este programa.", 409);
        return vincular(id, programa, actor.get("id"), activo, (String) fila.get("role"));
    }

    private Map<String, Object> vincular(long id, long programa, Object actor, boolean activo, String perfil) {
        long vinculo;
        if (activo) {
            vinculo = dao.insertar("INSERT INTO memberships(account_id,program_id,started_at,created_by) VALUES (?,?,?,?)",
                    id, programa, ahora(), actor);
        } else {
            var fila = dao.uno("SELECT id FROM memberships WHERE account_id=? AND program_id=? AND ended_at IS NULL",
                    id, programa);
            exigir(fila != null, "El vínculo no existe.", 404);
            vinculo = (long) fila.get("id");
            dao.ejecutar("UPDATE memberships SET ended_at=?,ended_by=? WHERE id=?", ahora(), actor, vinculo);
        }
        dao.registrar((perfil.equals("STUDENT") ? "Estudiante" : "Docente") + (activo ? "Vinculado" : "Desvinculado"),
                actor, "account", id, datos("program_id", programa, "membership_id", vinculo));
        return datos("id", vinculo, "active", activo);
    }

    public Map<String, Object> perfil(Map<String, Object> fila) {
        var publico = publica(fila);
        publico.put("memberships", dao.todos("""
                SELECT m.id,m.program_id,p.name,m.started_at,m.ended_at FROM memberships m
                JOIN programs p ON p.id=m.program_id WHERE m.account_id=? ORDER BY m.id DESC
                """, fila.get("id")));
        return publico;
    }

    public List<Map<String, Object>> administradas(Map<String, Object> actor) {
        var roles = AUTORIDAD.getOrDefault(actor.get("role"), Set.of()).stream().sorted().toList();
        if (roles.isEmpty()) return List.of();
        List<Object> parametros = new ArrayList<>();
        parametros.add(actor.get("id"));
        parametros.addAll(roles);
        String ambito = switch ((String) actor.get("role")) {
            case "PROGRAM" -> {
                parametros.add(actor.get("program_id"));
                yield "AND EXISTS (SELECT 1 FROM memberships m WHERE m.account_id=a.id AND m.program_id=? AND m.ended_at IS NULL)";
            }
            case "FACULTY" -> {
                parametros.add(actor.get("faculty_id"));
                yield "AND a.program_id IN (SELECT id FROM programs WHERE faculty_id=?)";
            }
            default -> "";
        };
        String marcadores = String.join(",", java.util.Collections.nCopies(roles.size(), "?"));
        var cuentas = dao.todos("SELECT a.* FROM accounts a WHERE a.id<>? AND a.role IN (" + marcadores + ") " + ambito
                + " ORDER BY a.id DESC", parametros.toArray()).stream().map(fila -> {
                    var publico = publica(fila);
                    var actual = titular(fila);
                    publico.put("occupant_id", actual == null ? null : actual.get("id"));
                    return publico;
                }).toList();
        if (Set.of("RECTOR", "PROGRAM").contains(actor.get("role"))) {
            dao.registrar("CuentasConsultadas", actor.get("id"), "account", actor.get("id"),
                    datos("count", cuentas.size()));
        }
        return cuentas;
    }

    public boolean limitado(String bucket, int maximo, long ventana) {
        long momento = epoch();
        var anterior = dao.uno("SELECT started,attempts FROM rate_limits WHERE bucket=?", bucket);
        if (anterior == null || momento - (long) anterior.get("started") >= ventana) {
            dao.ejecutar("""
                    INSERT INTO rate_limits VALUES (?,?,1)
                    ON CONFLICT(bucket) DO UPDATE SET started=excluded.started,attempts=1
                    """, bucket, momento);
            return false;
        }
        if ((int) anterior.get("attempts") >= maximo) return true;
        dao.ejecutar("UPDATE rate_limits SET attempts=attempts+1 WHERE bucket=?", bucket);
        return false;
    }

    public static long epoch() {
        return Instant.now().getEpochSecond();
    }

    private static CuentaDominio modelo(Map<String, Object> fila) {
        return new CuentaDominio((String) fila.get("status"), bandera(fila.get("email_verified")),
                bandera(fila.get("phone_verified")), fila.get("approved_at") != null);
    }

    private static Map<String, Object> publica(Map<String, Object> fila) {
        var publico = new java.util.LinkedHashMap<>(fila);
        publico.remove("password_hash");
        return publico;
    }

    private byte[] bytes(int cantidad) {
        byte[] valores = new byte[cantidad];
        aleatorio.nextBytes(valores);
        return valores;
    }

    private String nuevaClave(Object valor) {
        String clave = texto(valor, "Contraseña", 128);
        exigir(clave.codePointCount(0, clave.length()) >= 12, "Usa una contraseña de al menos 12 caracteres.");
        return hashClave(clave, HexFormat.of().formatHex(bytes(16)));
    }

    private static boolean coincideClave(String clave, String guardada) {
        return iguales(hashClave(clave, guardada.substring(0, 32)), guardada);
    }

    private static String hashClave(String clave, String sal) {
        var datos = new PBEKeySpec(clave.toCharArray(), HexFormat.of().parseHex(sal), 600_000, 256);
        try {
            return sal + ":" + HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(datos).getEncoded());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        } finally {
            datos.clearPassword();
        }
    }

    private String firma(Map<String, Object> fila, String canal, String codigo) {
        String valor = fila.get("id") + ":" + canal + ":" + fila.get(canal) + ":" + codigo;
        try {
            var firma = Mac.getInstance("HmacSHA256");
            firma.init(new SecretKeySpec(config.clave(), "HmacSHA256"));
            return HexFormat.of().formatHex(firma.doFinal(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String digest(String valor) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private static boolean iguales(String primero, String segundo) {
        return MessageDigest.isEqual(primero.getBytes(StandardCharsets.US_ASCII), segundo.getBytes(StandardCharsets.US_ASCII));
    }
}
