# Verificación de la entrega 2.0.0

## Cambios posteriores a la verificación registrada

Esta revisión añadió resolución segura de IP detrás de proxies configurados, paginación navegable, consultas agrupadas del dashboard y seis pruebas de regresión. **Esos cambios aún no se han ejecutado en Maven**: en el entorno de edición no está instalado/configurado un JDK (`JAVA_HOME` no está definido). Los resultados de 26 pruebas que siguen corresponden a la verificación histórica de la entrega 2.0.0 y no certifican estos cambios nuevos. Repite `mvn verify` con JDK 25 antes de desplegar.

Fecha: 30 de septiembre de 2026. Entorno: Windows, Java 21, Maven 3.9, Spring Boot 3.5.16.

## Verificación del runtime Java 25

Repetida el 23 de octubre de 2026 en Windows con Eclipse Temurin 25.0.3 LTS, Maven 3.9.16 y Spring Boot 3.5.16. `mvn clean test-compile -q` y `mvn clean test -q` finalizaron correctamente: 26 pruebas aprobadas, 0 fallos, 0 errores y 0 omitidas. Mockito emitió una advertencia sobre su auto-carga dinámica de agente; no afectó la compilación ni las pruebas.

## Resultados automatizados

| Entorno | Unitarias | Integración | Fallos / errores / omitidas |
|---|---:|---:|---:|
| H2 en modo PostgreSQL | 5 | 21 | 0 / 0 / 0 |
| PostgreSQL 17.11 real, esquema privado | 5 | 21 | 0 / 0 / 0 |

Ambas ejecuciones completaron `mvn verify` y generaron el JAR. Los informes de texto están en `docs/test-results/h2` y `docs/test-results/postgres`. Se corrigió una incompatibilidad detectada en PostgreSQL: las inserciones declaran explícitamente el tipo JDBC de las fechas con zona horaria.

Cobertura: login y BCrypt, CSRF, roles, aislamiento entre equipos, documentos privados, validaciones de archivos, PDF real, checklist con evidencia, edición concurrente, requisitos convertidos en tareas, revisión de sugerencias, alertas por fecha, envío simulado y rechazo de citas inventadas. No es una auditoría exhaustiva de seguridad ni una prueba de carga.

## Repetir con PostgreSQL

Crea una **base exclusiva para pruebas**, vacía y distinta de los datos del servicio. Flyway crea tablas y el arranque inserta un administrador de prueba; los casos de integración usan transacciones con rollback.

```powershell
$env:TEST_DATABASE_URL='jdbc:postgresql://localhost:5432/licitagestion_test?currentSchema=licitagestion_private'
$env:TEST_DATABASE_USER='usuario_de_pruebas'
$env:TEST_DATABASE_PASSWORD='clave_de_pruebas'
$env:TEST_DATABASE_SCHEMA='licitagestion_private'
mvn verify
```

Para volver a H2, elimina esas cuatro variables de la sesión de terminal y ejecuta `mvn verify`. No cambies la configuración de una base en uso para ejecutar estos tests.

## Recorrido en navegador

Se comprobó con el JAR de esta entrega y la base de demo persistente:

- Inicio de sesión y visualización del dashboard.
- Apertura de un expediente, documentos y sugerencias extraídas de su PDF.
- Pregunta sobre documentos faltantes: devuelve el conteo, los dos requisitos documentales pendientes y sus fuentes, excluyendo un requisito técnico ya completado.
- Edición y aceptación de una sugerencia: pasa a Aceptada y aparece en el checklist con su fuente y responsable.
- Conversión del requisito en tarea y guardado del estado Completada: deja de mostrarse Atrasada.

La base demo conserva cambios y fechas entre reinicios. Las fechas vencidas se muestran como atrasadas de forma deliberada; no se alteran silenciosamente para la presentación.

## Límites de la comprobación

No se hicieron llamadas reales a LLM, Meta/WhatsApp ni Supabase. No se ejecutaron Docker, despliegue cloud, HTTPS con certificado ni restauración de backups. Sus configuraciones/adaptadores están incluidos, y deben verificarse en el entorno elegido. El asistente de la demo es extractivo local, sin modelo generativo ni OCR.

