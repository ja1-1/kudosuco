# Back-End de UCO Kudos

API Spring Boot con Java 25 y PostgreSQL. Flyway aplica las migraciones de `src/main/resources/db/migration` al iniciar.

- Para ejecutar la aplicación completa, sigue el manual de puesta a punto de la documentación de entrega.
- Desde `backend/`, ejecuta `./mvnw verify` para compilar y ejecutar las pruebas. Para habilitar las pruebas de integración, configura `KUDOS_TEST_DATABASE_URL`, `DATABASE_USER` y `DATABASE_PASSWORD` con un PostgreSQL de pruebas.
