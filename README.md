# LicitaGestión · sistema privado de seguimiento

Nueva versión Java del proyecto. Cambia el objetivo: **gestionar las licitaciones ya recibidas**, desde el registro hasta la presentación y resultado. No busca oportunidades, no hace matching y no consulta SEACE.

Stack: **Java 25 (LTS), Spring Boot 3.5, Spring Security, Spring JDBC, Flyway, PostgreSQL**, interfaz HTML/CSS/JavaScript y una página de acceso con Thymeleaf. PDFBox extrae texto de PDF. No requiere Node ni CDN.

## Qué puedes hacer

- Acceder con cuentas privadas creadas por un administrador; roles Administrador, Gestor y Consulta.
- Registrar código, nombre, entidad, descripción, publicación, cierre con hora, estado, prioridad, responsable, observaciones y equipo autorizado.
- Seguir los nueve estados solicitados: Recibida, En revisión, En preparación, Pendiente de documentos, Lista para presentar, Presentada, Adjudicada, No adjudicada y Cancelada.
- Subir y descargar documentos **PDF/TXT privados**, clasificados por tipo; extraer texto por página.
- Registrar requisitos con tipo, estado, responsable, fecha, evidencia documental y observaciones. Convertir un requisito en tarea sin duplicarla.
- Administrar tareas y fechas importantes, con vencimientos exactos y responsables.
- Consultar el dashboard, la agenda y alertas sobre procesos, tareas, documentos y requisitos.
- Analizar documentos al subirlos, revisar sugerencias y aceptarlas, modificarlas o descartarlas antes de incorporarlas al checklist/agenda.
- Consultar un asistente acotado al proceso, con citas a páginas de documentos o registros internos.
- Preparar avisos WhatsApp, revisar destinatario/contenido y simular el envío. Incluye adaptador de **Cloud API oficial** para activar con credenciales y plantilla aprobada.
- Administrar usuarios y ver el registro de acciones importantes.

## Alcance de las integraciones

| Componente | Estado |
|---|---|
| Seguimiento, permisos, archivos, checklist, tareas, agenda | Implementados localmente |
| Extracción de texto PDF con PDFBox | Implementada; PDF con texto, no OCR |
| Asistente local | Implementado por búsqueda de fragmentos y reglas; **no es un LLM** |
| Adaptador LLM | Implementado para API HTTPS compatible con Chat Completions y modo JSON; sin llamadas reales mientras no se configure |
| WhatsApp | Simulación predeterminada; adaptador oficial implementado, activación requiere credenciales, consentimiento y plantilla aprobada |
| PostgreSQL/Supabase | Perfil PostgreSQL y migración Flyway incluidos; el backend usa JDBC, no Supabase Auth ni Data API |
| Supabase Storage | No implementado: almacenamiento privado local detrás del backend |
| HTTPS | Configurable para despliegue; certificado/proxy no provisionados por el proyecto |

No se crearon cuentas cloud, no se compraron servicios y no se enviaron documentos o avisos a terceros durante el desarrollo de la demo.

## Ejecutar la demo local

Requisitos: **JDK 25 (LTS)** y **Maven 3.9+**. `JAVA_HOME` debe apuntar a la carpeta del JDK. Comprueba `java -version` y `mvn -version`.

Desde esta carpeta:

```powershell
mvn clean verify
java -jar target/licitagestion-2.0.0.jar --spring.profiles.active=demo
```

Abre [http://127.0.0.1:8080](http://127.0.0.1:8080). También puedes ejecutar `./scripts/run-demo.ps1` en PowerShell. En Linux/macOS funcionan los mismos comandos Maven/Java.

El perfil demo usa **H2 persistente en `data/demo`**, con sintaxis de compatibilidad PostgreSQL, para mostrar el producto sin instalar un servidor. No se presenta H2 como sustituto de PostgreSQL en producción. La primera ejecución genera procesos ficticios, un PDF de bases, tareas, requisitos y fechas relativas al momento de creación. Reiniciar conserva cambios; no regenera fechas.

| Usuario | Rol | Contraseña de demo |
|---|---|---|
| `admin@example.com` | Administrador | `DemoUniversitaria2026!` |
| `gestor@example.com` | Gestor, Ana | `DemoUniversitaria2026!` |
| `consulta@example.com` | Consulta, Carlos | `DemoUniversitaria2026!` |
| `otro@example.com` | Gestor de otro equipo, Lucía | `DemoUniversitaria2026!` |

La cuenta `otro` tiene un proceso que Ana/Carlos no pueden ver. El administrador ve todos. Estas cuentas se crean **solo al inicializar una base vacía con seed de demo activo**.

## PostgreSQL local o Supabase

1. Crea una base PostgreSQL y un usuario dedicado. Para desarrollo local puede ser dueño de esa base para que Flyway cree su esquema. En producción separa el rol de migraciones del rol de ejecución.
2. Copia `.env.example` a `.env`. Spring importa el archivo como propiedades; escribe valores **sin comillas**. Las variables de entorno del proceso prevalecen.
3. Configura `DB_URL`, `DB_USER`, `DB_PASSWORD`, `BOOTSTRAP_EMAIL` y `BOOTSTRAP_PASSWORD`.
4. Ejecuta:

```powershell
java -jar target/licitagestion-2.0.0.jar --spring.profiles.active=postgres
```

Ejemplo de conexión local:

```dotenv
DB_URL=jdbc:postgresql://localhost:5432/licitagestion?currentSchema=licitagestion_private
DB_USER=licitagestion
DB_PASSWORD=tu-clave-local
BOOTSTRAP_EMAIL=administrador@tuempresa.com
BOOTSTRAP_PASSWORD=una-clave-larga-y-unica
```

Flyway crea el esquema `licitagestion_private` y aplica `src/main/resources/db/migration/V1__private_workflow.sql`. Los datos de aplicación quedan fuera del esquema público. **En Supabase no expongas este esquema mediante Data API y no concedas acceso a `anon`/`authenticated`**: los usuarios se autentican con Spring Security y todos los accesos pasan por el backend.

Para Supabase, copia el host/usuario/puerto del panel de conexión a PostgreSQL. Usa conexión directa si tu red soporta su direccionamiento, o el pooler en **modo sesión** para una aplicación Java persistente. Una URL orientativa, con valores reales de tu proyecto, es:

```dotenv
DB_URL=jdbc:postgresql://HOST:5432/postgres?currentSchema=licitagestion_private&sslmode=verify-full
DB_USER=USUARIO_DEL_PANEL
DB_PASSWORD=CONTRASENA_DE_BASE_DE_DATOS
```

Configura el certificado raíz que exija tu conexión para `verify-full`. `sslmode=require` cifra la conexión pero no valida identidad del servidor; no lo confundas con verificación completa. El token de Supabase no reemplaza la contraseña PostgreSQL. El proyecto no crea ni configura automáticamente un proyecto Supabase.

Con una base vacía, el primer inicio crea **solo** el administrador configurado. `BOOTSTRAP_PASSWORD` no restablece una cuenta existente. Crea el resto desde “Equipo y permisos”. No hay registro público.

## Docker

Configura `.env` como arriba y ejecuta:

```bash
docker compose up --build -d
docker compose logs web
```

Compose inicia PostgreSQL y el servidor web; Flyway migra al arrancar. Usa volúmenes separados para BD y documentos, y publica puertos solo en loopback. El backend escucha en la red interna del contenedor. `docker compose down` conserva volúmenes. Las contraseñas de inicialización de PostgreSQL no se cambian modificando `.env` después de crear el volumen.

La construcción del contenedor ejecuta pruebas. Para un despliegue real fija imágenes por digest, aplica actualizaciones y configura TLS/secretos/backups. El Compose suministrado es un entorno local, no una infraestructura cloud aprovisionada.

## Recorrido para mostrarlo

1. Ingresa como administrador y revisa Resumen: procesos activos, plazos próximos, atrasos y pendientes.
2. Abre `LP-2026-014`. Revisa el responsable y las personas autorizadas.
3. En Documentos descarga el PDF generado de demo. La descarga requiere sesión y permiso del proceso.
4. En Revisión de análisis abre una sugerencia, cambia su descripción, selecciona responsable y fecha y acepta. Aparecerá en el checklist o agenda según su tipo.
5. Para un requisito documental, sube el certificado/declaración correspondiente y vincúlalo antes de marcarlo completo. La base original **no es automáticamente evidencia de cumplimiento**.
6. Convierte un requisito en tarea y edita su estado. Completar la tarea no completa automáticamente el requisito.
7. Pregunta al asistente “¿Qué documentos faltan?” o “¿Quién es responsable de las tareas pendientes?” y revisa las citas.
8. Cambia entre cuentas para comprobar el aislamiento. Consulta puede leer, no editar; gestor solo gestiona los procesos asignados; administrador gestiona todo.
9. Desde Alertas prepara un aviso para un usuario con número y consentimiento configurados. Revisa la vista previa y usa “Simular envío”. En modo predeterminado no sale ningún mensaje.

## Asistente y análisis

`AssistantProvider` define `extract(sources)` y `answer(question, sources)`. Hay dos adaptadores:

- `ExtractiveProvider`: local, determinista y sin red. Reconoce líneas sobre requisitos, documentos, fechas, montos, experiencia, condiciones, criterios y penalidades. Devuelve fragmentos literales, no un resumen semántico completo. Las preguntas se resuelven por coincidencia de palabras; si no hay coincidencias, indica ausencia de información.
- `LlmProvider`: cliente HTTP con timeout para un endpoint Chat Completions compatible con `response_format: {type: json_object}`. El servidor valida estructura, límites, IDs de fuentes y que las citas sean subcadenas exactas del contexto. Los resultados aún necesitan revisión; una cita válida no demuestra que toda interpretación generada sea correcta.

Para activar un proveedor real:

```dotenv
AI_MODE=llm
LLM_URL=https://api.openai.com/v1/chat/completions
LLM_API_KEY=tu-clave
LLM_MODEL=un-modelo-compatible-disponible-en-tu-cuenta
LLM_DOCUMENT_TRANSFER_ENABLED=true
```

Debes seleccionar un modelo que admita ese endpoint y modo JSON; no se inventa un modelo predeterminado ni se garantiza acceso a uno. La bandera de transferencia evita enviar documentos privados por accidente. La clave nunca se envía al navegador. La consulta es síncrona, con contexto máximo de 150 000 caracteres; no hay embeddings, RAG vectorial ni OCR. El sistema incluye registros internos (tareas, requisitos y fechas) como fuentes diferenciadas para poder responder preguntas operativas.

Los documentos son inmutables en este MVP; se puede subir una adenda como documento nuevo. Reanalizar el mismo documento con el mismo proveedor reutiliza su análisis, con bloqueo de fila para evitar duplicados. Las sugerencias aceptadas o descartadas no se procesan dos veces.

## WhatsApp oficial

`WHATSAPP_MODE=dry-run` es el modo predeterminado. Guarda la simulación y nunca contacta a Meta. Para habilitar el adaptador:

```dotenv
WHATSAPP_MODE=cloud
WHATSAPP_TOKEN=token-servidor
WHATSAPP_PHONE_ID=id-del-numero-de-la-empresa
WHATSAPP_API_VERSION=version-vigente-de-tu-aplicacion
WHATSAPP_TEMPLATE=nombre_de_plantilla_aprobada
WHATSAPP_LANGUAGE=es
```

La versión debe tener formato `vNN.N`. La plantilla de utilidad aprobada debe aceptar **un parámetro de texto en el cuerpo**, donde se insertará el aviso mostrado. Debes configurar los activos de WhatsApp Business, permisos del token, plantilla y destinatarios habilitados en Meta. Se envía a `https://graph.facebook.com/{version}/{phone-id}/messages` mediante HTTPS y autorización Bearer.

El administrador registra teléfono y consentimiento del usuario; el destinatario debe conservar acceso al proceso. Cada borrador conserva la huella del contenido y destinatario. Antes de enviar se revalida que la alerta siga activa y no haya cambiado. El estado pasa de borrador a enviando una sola vez. Ante respuesta ambigua se marca `INCIERTA` y no se reintenta automáticamente. `ACEPTADA_META` significa aceptación de la API, **no entrega**: no se implementaron webhooks de entrega/lectura.

No hay campañas ni envíos automáticos programados. Las alertas se calculan automáticamente al consultar el dashboard y este se refresca cada minuto; **el envío externo requiere una acción explícita del administrador**. Para automatizarlo con políticas de empresa, añade un worker/outbox y controles descritos en arquitectura.

## Reglas importantes

- `ATRASADA` en tareas es un estado calculado: una tarea pendiente/en progreso cuyo plazo ya pasó. Así no queda atrasada después de completarla.
- Un proceso presentado/adjudicado/no adjudicado/cancelado deja de generar alertas de preparación; permanece en el catálogo para actualizar su resultado.
- “Lista para presentar” requiere no tener requisitos pendientes. La app no valida cumplimiento legal ni presenta propuestas por ti.
- Un requisito de tipo Documento no puede completarse sin vincular un documento del **mismo proceso**.
- Una sugerencia de fecha se convierte en hito; no altera automáticamente la fecha oficial de presentación.
- La edición usa versiones para detectar cambios concurrentes. Si alguien guardó antes, se responde 409 y debes refrescar.
- Las tareas, requisitos y fechas conservan al responsable. Debes reasignarlos antes de retirarle el acceso al proceso.
- No se borran físicamente procesos, documentos o historial desde la UI; cancela un proceso o marca no aplicable un requisito para conservar trazabilidad.

## Seguridad y operación

Contraseñas BCrypt con coste 12; CSRF activo en formularios y API JSON/multipart; sesiones HttpOnly/SameSite, fijación de sesión protegida; limitador de login de cinco fallos por IP en 15 minutos (en memoria, una instancia); consultas SQL parametrizadas; escape de contenido en la UI; CSP restrictiva; controles de autorización también en descargas/chat; carga limitada por tamaño, extensión, firma PDF y parseo; archivos nombrados por UUID fuera de recursos web; logs de actividad sin claves/tokens. `X-Forwarded-For` solo se acepta si el origen coincide con `TRUSTED_PROXY_ADDRESSES`; configura proxies controlados y bloquea el acceso directo al backend.

Para HTTPS configura un proxy con certificado válido y `COOKIE_SECURE=true`. Puedes usar `REQUIRE_HTTPS=true` cuando el servidor reciba solicitudes HTTPS. Detrás de un proxy, configura los encabezados reenviados **solo si el backend queda accesible exclusivamente desde ese proxy de confianza**; de otro modo podrías confiar en encabezados falsificados. No se incluye certificado ni se habilita HTTPS en el demo HTTP local.

Documentos escaneados sin texto muestran aviso de OCR pendiente. Se admiten hasta 100 páginas, 10 MB y 120 000 caracteres por documento. No hay antivirus, aislamiento del parser ni detección exhaustiva de PDF malicioso; incorpora esos controles antes de aceptar documentos de orígenes no confiables a escala.

Consulta [`docs/arquitectura.md`](docs/arquitectura.md) para diseño, permisos, esquema y evolución; [`docs/operacion.md`](docs/operacion.md) para backups, despliegue y límites pendientes.

## Pruebas

```bash
mvn test
mvn clean verify
```

Pruebas unitarias del tiempo y análisis extractivo, y pruebas de integración Spring/MockMvc: autenticación/CSRF, permisos, descargas privadas, aislamiento de chat, edición concurrente, checklist/evidencia, tareas, revisión humana, PDF real, archivos inválidos, alertas y WhatsApp simulado. La última verificación registrada de la entrega 2.0.0 aprobó 26 pruebas con H2 y las mismas 26 con PostgreSQL 17.11 real; las pruebas añadidas después de esa verificación siguen pendientes de ejecutar con JDK 25. No se transmiten mensajes ni se llama a un LLM real desde las pruebas. Resultados y pasos para repetirlas: [`docs/verificacion.md`](docs/verificacion.md).

## Qué pasó con el MVP anterior

El proyecto Flask se conserva en la carpeta hermana `licitamatch`. Esta versión vive en `licitagestion`; usa otra base y otro puerto. **No se hizo una migración automática de los datos Flask**, porque cambiaron permisos, estados y modelo de trabajo. Para datos reales prepara un importador revisable que asigne responsables/accesos explícitamente; no conviertas un catálogo compartido en documentos privados por suposición.

## Referencias oficiales

- [Spring Boot: requisitos](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
- [PDFBox 3: carga de PDF y extracción](https://pdfbox.apache.org/3.0/migration.html).
- [Supabase: conexiones PostgreSQL](https://supabase.com/docs/guides/database/connecting-to-postgres) y [SSL](https://supabase.com/docs/guides/platform/ssl-enforcement).
- [OpenAI Docs: salidas estructuradas y modo JSON](https://developers.openai.com/api/docs/guides/structured-outputs).
- [Meta: envío de plantillas WhatsApp](https://whatsapp.github.io/WhatsApp-Nodejs-SDK/api-reference/messages/template/).

