package kudos.configuracion;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kudos")
public record KudosConfiguracion(String demo, String origin, String institution, String verificationKey) {
    public KudosConfiguracion {
        institution = institution.strip().toLowerCase(Locale.ROOT);
        if (!origin.isEmpty()) {
            var uri = URI.create(origin);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !origin.equals(uri.getScheme() + "://" + uri.getRawAuthority())
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                throw new IllegalArgumentException("KUDOS_ORIGIN debe contener esquema, host y puerto opcional.");
            }
        }
        if (!"1".equals(demo) && (!origin.startsWith("https://") || verificationKey.length() < 32)) {
            throw new IllegalArgumentException("Configura KUDOS_ORIGIN HTTPS y KUDOS_VERIFICATION_KEY de al menos 32 caracteres.");
        }
    }

    public boolean demostracion() {
        return "1".equals(demo);
    }

    public byte[] clave() {
        return (verificationKey.isEmpty() ? "kudos-local-demo-key" : verificationKey).getBytes(StandardCharsets.UTF_8);
    }
}
