package kudos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import kudos.configuracion.KudosConfiguracion;

@SpringBootApplication
@EnableConfigurationProperties(KudosConfiguracion.class)
public class KudosApplication {
    static void main(String[] args) {
        SpringApplication.run(KudosApplication.class, args);
    }
}
