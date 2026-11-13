package kudos.servicio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

import kudos.dao.datos.postgresql.KudosPostgresqlDAO;
import kudos.dominio.CategoriaDominio;
import kudos.dominio.ContenidoDominio;
import kudos.dominio.valor.Estrellas;

import static kudos.dao.datos.postgresql.KudosPostgresqlDAO.ahora;
import static kudos.dominio.ReglaException.exigir;
import static kudos.dominio.ReglasKudos.*;
import static kudos.servicio.Datos.*;

@Service
public class ReconocimientoServicio {
    private static final String VISTA = """
            SELECT c.*,a.name AS author,u.name AS recipient,
                   (SELECT round(avg(r.stars),2) FROM ratings r WHERE r.content_id=c.id) AS average,
                   (SELECT count(*) FROM ratings r WHERE r.content_id=c.id) AS rating_count,
                   (SELECT r.stars FROM ratings r WHERE r.content_id=c.id AND r.account_id=?) AS my_stars
            FROM content c JOIN accounts a ON a.id=c.author_id LEFT JOIN accounts u ON u.id=c.recipient_id
            """;
    private final KudosPostgresqlDAO dao;

    public ReconocimientoServicio(KudosPostgresqlDAO dao) {
        this.dao = dao;
    }

    Map<String, Object> contenido(Object id, boolean ordinario) {
        var fila = dao.buscarContenido(id);
        exigir(fila != null, "Contenido inexistente.", 404);
        if (ordinario) {
            estado(fila).interactuar();
            exigir(!dao.tieneAncestroOculto(fila.get("parent_id")), "Contenido no disponible.", 404);
        }
        return fila;
    }

    public Map<String, Object> personas(Map<String, Object> consulta) {
        String busqueda = texto(consulta.getOrDefault("q", ""), "Búsqueda", 100, true).strip();
        return pagina(dao.todos("""
                SELECT id,name,role FROM accounts WHERE status='ACTIVE' AND id>?
                AND (strpos(lower(name),lower(?))>0 OR CAST(id AS text)=?) ORDER BY id LIMIT 51
                """, consulta.getOrDefault("after", 0L), busqueda, busqueda), 50, "id");
    }

    public Map<String, Object> muro(Map<String, Object> actor, Map<String, Object> consulta) {
        List<Object> parametros = new ArrayList<>();
        parametros.add(actor.get("id"));
        List<String> filtros = new ArrayList<>();
        filtros.add("c.kind='KUDO'");
        filtros.add("c.state IN ('VISIBLE','REPORTED','IN_REVIEW')");
        Map.of("sent", "c.author_id=?", "received", "c.recipient_id=?", "category", "c.category_id=?",
                "before", "c.id<?").forEach((clave, predicado) -> {
                    if (consulta.containsKey(clave)) {
                        filtros.add(predicado);
                        parametros.add(consulta.get(clave));
                    }
                });
        return pagina(dao.todos(VISTA + "WHERE " + String.join(" AND ", filtros) + " ORDER BY c.id DESC LIMIT 21",
                parametros.toArray()), 20, "id");
    }

    public Map<String, Object> detalle(Map<String, Object> actor, long id) {
        exigir("KUDO".equals(contenido(id, true).get("kind")), "Selecciona un Kudo.", 404);
        var filas = dao.todos("""
                WITH RECURSIVE thread(id) AS (
                    SELECT ?::bigint
                    UNION ALL
                    SELECT child.id FROM content child JOIN thread t ON child.parent_id=t.id
                    WHERE child.state IN ('VISIBLE','REPORTED','IN_REVIEW')
                )
                """ + VISTA + "JOIN thread t ON t.id=c.id ORDER BY c.id", id, actor.get("id"));
        dao.registrar("KudoConsultado", actor.get("id"), "content", id, Map.of());
        return datos("kudo", filas.getFirst(), "replies", filas.subList(1, filas.size()));
    }

    public Map<String, Object> publicar(Map<String, Object> actor, Map<String, Object> entrada) {
        long destinatario = identificador(entrada.get("recipient_id"), "Destinatario");
        exigir(!Objects.equals(destinatario, actor.get("id")), "El reconocimiento debe dirigirse a otra persona.");
        var receptor = dao.uno("SELECT status FROM accounts WHERE id=?", destinatario);
        exigir(receptor != null && "ACTIVE".equals(receptor.get("status")), "El destinatario no está activo.");
        long categoriaId = identificador(entrada.get("category_id"), "Categoría");
        var categoria = dao.uno("SELECT name,description,active FROM categories WHERE id=?", categoriaId);
        exigir(categoria != null, "Categoría inexistente.", 404);
        var datosCategoria = new CategoriaDominio((String) categoria.get("name"), (String) categoria.get("description"),
                bandera(categoria.get("active"))).instantanea();
        String mensaje = texto(entrada.get("body"), "Reconocimiento");
        long id = dao.insertar("""
                INSERT INTO content(kind,author_id,recipient_id,category_id,category_name,category_description,body,created_at,state)
                VALUES ('KUDO',?,?,?,?,?,?,?,'CREATED')
                """, actor.get("id"), destinatario, categoriaId, datosCategoria.getFirst(), datosCategoria.getLast(), mensaje, ahora());
        publicarContenido(id, actor.get("id"), "KudoPublicado", datos("recipient_id", destinatario, "category_id", categoriaId));
        return datos("id", id);
    }

    public Map<String, Object> responder(Map<String, Object> actor, long padre, Map<String, Object> entrada) {
        var anterior = contenido(padre, true);
        String mensaje = texto(entrada.get("body"), "Réplica");
        Object raiz = anterior.get("root_id") == null ? padre : anterior.get("root_id");
        long id = dao.insertar("""
                INSERT INTO content(kind,author_id,root_id,parent_id,body,created_at,state) VALUES ('REPLY',?,?,?,?,?,'CREATED')
                """, actor.get("id"), raiz, padre, mensaje, ahora());
        publicarContenido(id, actor.get("id"), "ReplicaPublicada", datos("root_id", raiz, "parent_id", padre));
        if ("REPLY".equals(anterior.get("kind"))) {
            dao.registrar("ReplicaRespondida", actor.get("id"), "content", padre, datos("reply_id", id));
        }
        return datos("id", id, "root_id", raiz);
    }

    public Map<String, Object> valorar(Map<String, Object> actor, long id, Map<String, Object> entrada) {
        contenido(id, true);
        int estrellas = Estrellas.validar(entrada.get("stars")).valor();
        Object usuario = actor.get("id");
        var anterior = dao.uno("SELECT stars FROM ratings WHERE content_id=? AND account_id=?", id, usuario);
        if (anterior == null || ((Number) anterior.get("stars")).intValue() != estrellas) {
            String fecha = ahora();
            dao.ejecutar("""
                    INSERT INTO ratings VALUES (?,?,?,?,?) ON CONFLICT(content_id,account_id)
                    DO UPDATE SET stars=excluded.stars,updated_at=excluded.updated_at
                    """, id, usuario, estrellas, fecha, fecha);
            dao.registrar(anterior == null ? "CalificacionRegistrada" : "CalificacionActualizada", usuario, "content", id,
                    datos("stars", estrellas, "previous", anterior == null ? null : anterior.get("stars")));
            var indicadores = indicadores(id);
            dao.registrar("IndicadorRecalculado", usuario, "content", id, indicadores);
            return indicadores;
        }
        return indicadores(id);
    }

    public Map<String, Object> valoraciones(long id, Map<String, Object> consulta) {
        contenido(id, true);
        var filas = dao.todos("""
                SELECT account_id, stars, created_at, updated_at FROM ratings
                WHERE content_id=? AND account_id>? ORDER BY account_id LIMIT 51
                """, id, consulta.getOrDefault("after", 0L));
        var pagina = pagina(filas, 50, "account_id");
        for (var fila : filas) {
            fila.remove("account_id");
        }
        return pagina;
    }

    private Map<String, Object> indicadores(long id) {
        return dao.uno("SELECT round(avg(stars),2) AS average,count(*) AS rating_count FROM ratings WHERE content_id=?", id);
    }

    void guardarEstado(Object id, String estado) {
        dao.guardarEstadoContenido(id, estado);
    }

    static ContenidoDominio estado(Map<String, Object> contenido) {
        return new ContenidoDominio((String) contenido.get("state"));
    }

    private void publicarContenido(long id, Object actor, String evento, Map<String, Object> hechos) {
        guardarEstado(id, new ContenidoDominio("CREATED").publicar());
        dao.registrar(evento, actor, "content", id, hechos);
    }
}
