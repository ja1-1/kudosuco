package kudos.dao.datos.postgresql;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class KudosPostgresqlDAO {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final TransactionTemplate lectura;
    private final TransactionTemplate escritura;

    public KudosPostgresqlDAO(JdbcTemplate jdbc, JdbcTransactionManager transacciones, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.lectura = new TransactionTemplate(transacciones);
        lectura.setReadOnly(true);
        lectura.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.escritura = new TransactionTemplate(transacciones);
    }

    public <T> T transaccion(boolean modifica, Supplier<T> operacion) {
        return (modifica ? escritura : lectura).execute(status -> {
            if (modifica) {
                jdbc.execute("SET LOCAL lock_timeout = '5s'");
                // Serializa las escrituras que dependen del estado leído en esta transacción.
                jdbc.execute("SELECT pg_advisory_xact_lock(49380161)");
            }
            return operacion.get();
        });
    }

    public List<Map<String, Object>> todos(String sql, Object... parametros) {
        return jdbc.queryForList(sql, parametros);
    }

    public Map<String, Object> uno(String sql, Object... parametros) {
        var filas = todos(sql, parametros);
        return filas.isEmpty() ? null : filas.getFirst();
    }

    public int ejecutar(String sql, Object... parametros) {
        return jdbc.update(sql, parametros);
    }

    public long insertar(String sql, Object... parametros) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class, parametros);
    }

    public void registrar(String evento, Object actor, String sujeto, Object id, Map<String, Object> datos) {
        ejecutar("INSERT INTO audit(event,actor_id,subject,subject_id,occurred_at,facts) VALUES (?,?,?,?,?,?)",
                evento, actor, sujeto, id, ahora(), json.writeValueAsString(datos));
    }

    public Map<String, Object> hechos(String datos) {
        return json.readValue(datos, new TypeReference<>() {});
    }

    public Map<String, Object> buscarContenido(Object id) {
        return uno("SELECT * FROM content WHERE id=?", id);
    }

    public boolean tieneAncestroOculto(Object padre) {
        return uno("""
                WITH RECURSIVE lineage(id,parent_id,state) AS (
                    SELECT id,parent_id,state FROM content WHERE id=?
                    UNION ALL
                    SELECT c.id,c.parent_id,c.state FROM content c JOIN lineage l ON c.id=l.parent_id
                ) SELECT 1 FROM lineage WHERE state NOT IN ('VISIBLE','REPORTED','IN_REVIEW') LIMIT 1
                """, padre) != null;
    }

    public void guardarEstadoContenido(Object id, String estado) {
        ejecutar("UPDATE content SET state=? WHERE id=?", estado, id);
    }

    public static String ahora() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
