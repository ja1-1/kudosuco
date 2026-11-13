package kudos.configuracion;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration
public class PersistenciaConfiguracion {
    @Bean
    JdbcTransactionManager transacciones(DataSource datos) {
        return new JdbcTransactionManager(datos);
    }
}
