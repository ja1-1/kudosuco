package kudos.configuracion;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SeguridadConfiguracion {
    @Bean
    SecurityFilterChain seguridad(HttpSecurity http, KudosConfiguracion config) {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reglas -> reglas.anyRequest().permitAll())
                .headers(cabeceras -> cabeceras
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"))
                        .referrerPolicy(politica -> politica.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .addFilterAfter(new OrigenKudos(config), HeaderWriterFilter.class)
                .build();
    }

    private static class OrigenKudos extends OncePerRequestFilter {
        private final KudosConfiguracion config;

        OrigenKudos(KudosConfiguracion config) {
            this.config = config;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest solicitud, HttpServletResponse respuesta, FilterChain cadena)
                throws IOException, ServletException {
            String host = solicitud.getHeader("Host");
            String esperado = config.origin();
            boolean hostValido = host != null && (esperado.isEmpty()
                    ? host.matches("(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?")
                    : host.equals(URI.create(esperado).getRawAuthority()));
            if (!hostValido) {
                rechazar(respuesta, 400, "Host inválido.");
                return;
            }
            String origen = solicitud.getHeader("Origin");
            if (!Set.of("GET", "HEAD").contains(solicitud.getMethod()) && origen != null
                    && !origen.equals(esperado.isEmpty() ? "http://" + host : esperado)) {
                rechazar(respuesta, 403, "Origen inválido.");
                return;
            }
            String reenviado = solicitud.getHeader("X-Kudos-Client-IP");
            if (!config.demostracion() && reenviado != null && InetAddress.ofLiteral(solicitud.getRemoteAddr()).isLoopbackAddress()) {
                try {
                    solicitud.setAttribute("kudos.client-ip", InetAddress.ofLiteral(reenviado).getHostAddress());
                } catch (IllegalArgumentException error) {
                    solicitud.setAttribute("kudos.client-ip", solicitud.getRemoteAddr());
                }
            }
            cadena.doFilter(solicitud, respuesta);
        }

        private static void rechazar(HttpServletResponse respuesta, int estado, String error) throws IOException {
            respuesta.setStatus(estado);
            respuesta.setContentType("application/json");
            respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
            respuesta.getWriter().write("{\"error\":\"" + error + "\"}");
        }
    }
}
