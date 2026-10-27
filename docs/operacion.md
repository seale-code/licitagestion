# Operación y despliegue

## Configuración

- `SPRING_PROFILES_ACTIVE`: `demo` (H2) o `postgres`.
- `APP_PORT` / `APP_HOST`: predeterminados 8080 y 127.0.0.1. Compose cambia el host dentro del contenedor a 0.0.0.0 y limita el puerto publicado a loopback.
- `APP_ZONE`: calendario visible de la empresa, por defecto America/Lima.
- `APP_STORAGE`: directorio privado persistente fuera de archivos estáticos.
- `DB_URL`, `DB_USER`, `DB_PASSWORD`: conexión JDBC. En Supabase usa un esquema privado no expuesto en Data API.
- `BOOTSTRAP_EMAIL`, `BOOTSTRAP_PASSWORD`: administrador de la primera inicialización; no restablecen cuentas existentes.
- `COOKIE_SECURE`, `REQUIRE_HTTPS`: usar con HTTPS correctamente configurado.
- `TRUSTED_PROXY_ADDRESSES`: direcciones IP literales separadas por coma de los proxies que pueden enviar `X-Forwarded-For`. Vacío por defecto; si el proxy no está listado, se usa la dirección TCP observada por la aplicación.
- `AI_MODE`, `LLM_*`: proveedor de IA y permiso de transferencia.
- `WHATSAPP_*`: modo, credenciales y plantilla oficial.

El perfil demo incluye credenciales conocidas; está orientado a una máquina local. `.env`, `data/` y `target/` están excluidos del control de versiones/paquete fuente. No pegues claves reales en el código ni en capturas.

El inicio de sesión limita a cinco fallos por IP en una ventana de 15 minutos. El estado reside en memoria: se reinicia con la aplicación y no se comparte entre réplicas. La IP reenviada solo se acepta si la conexión viene de una dirección incluida en `TRUSTED_PROXY_ADDRESSES`; configura únicamente proxies controlados por la organización y bloquea el acceso directo al backend en el firewall. Para varias réplicas, reemplaza el limitador local por almacenamiento compartido antes de publicar el servicio.

## Backups

Se deben respaldar **BD y documentos**. Un dump sin los archivos no permite recuperar las descargas; un ZIP sin BD pierde permisos y relaciones.

El script `scripts/backup.ps1` usa `pg_dump` y comprime la carpeta de documentos, generando checksums. Requiere herramientas cliente PostgreSQL y una conexión configurada mediante `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD` o `pgpass`. Ejemplo:

```powershell
# Configura las variables de conexión sin publicarlas ni guardarlas en el repositorio.
./scripts/backup.ps1 -Database licitagestion -DocumentsPath ./data/private-documents -Destination ./data/backups
```

Los documentos son inmutables y no se eliminan físicamente desde la aplicación: un dump seguido de la copia de archivos puede contener archivos adicionales, pero conserva los archivos a los que el dump hace referencia. En una evolución con eliminación/versionado de archivos deberá utilizarse una instantánea coordinada.

Conserva copias cifradas fuera del servidor, con acceso restringido, retención definida y alertas de fallos. Los backups también contienen datos privados. El script no configura un cron ni copia a cloud por sí solo.

### Ensayo de restauración

1. Crea una base vacía separada del servicio en uso, con una cuenta adecuada.
2. Restaura el dump con `pg_restore --no-owner --dbname=NOMBRE_BASE archivo.dump` y revisa todos los errores.
3. Descomprime el directorio de documentos en una ubicación privada nueva.
4. Apunta una instancia aislada a esa BD/carpeta, con proveedor local y WhatsApp dry-run.
5. Verifica login, permisos entre cuentas, apertura de procesos y descarga de archivos citados.
6. No consideres válido un backup solo porque el comando terminó: confirma integridad y disponibilidad del flujo.

## Antes de producción pública

La entrega es un MVP funcional con base de despliegue. Quedan tareas operativas que no se sustituyen con una variable de entorno:

- Certificado, dominio, proxy y red privada de backend/BD.
- Control de intentos/rate limiting de login, archivos, chat y análisis. Añadir límites persistentes/compartidos si existen varias réplicas.
- Recuperación de contraseñas y MFA según riesgo. Cambiar una contraseña actualiza `password_updated_at`; las sesiones anteriores se invalidan al siguiente acceso a la API. Desactivar una cuenta también bloquea su acceso en la siguiente solicitud.
- Al desplegar detrás de proxy, configurar `TRUSTED_PROXY_ADDRESSES` con IPs literales y restringir la red para que el backend solo acepte conexiones desde esos proxies.
- Antivirus, parser aislado, límites de ejecución y pruebas con PDFs hostiles.
- Separación de rol de migraciones y aplicación en PostgreSQL; no exponer esquema por Supabase Data API.
- Gestión/rotación de secretos y permisos mínimos en Meta/proveedor LLM.
- Política de transferencia y retención de documentos; consentimiento y aprobación de plantillas WhatsApp.
- Pruebas de carga, alertas de salud/errores, métricas y restauración de backups.
- Registro de fallos de autenticación, retención de auditoría y estrategia contra manipulación del log.
- Escaneo de dependencias e imágenes, fijación de artefactos y actualización periódica.

## Diagnóstico rápido

| Síntoma | Revisión |
|---|---|
| Maven no encuentra Java | `JAVA_HOME` apunta al directorio JDK; `mvn -version` informa Java 25 |
| No aparece un proceso | Verifica rol y asignación. Fuera de alcance se responde 404 |
| 403 al guardar | Revisa rol/sesión/token CSRF; no desactives CSRF como solución |
| 409 al editar | Otra edición se guardó antes. Actualiza y vuelve a aplicar el cambio |
| PDF sin sugerencias | Puede no tener texto, estar escaneado o no contener patrones reconocibles localmente |
| Error al analizar después de subir | El documento permanece guardado. Reintenta o comprueba modo/credenciales/límites |
| No se puede completar documento | Vincula la evidencia al requisito del mismo proceso |
| No se puede dejar “Lista” | Quedan requisitos pendientes o en progreso |
| No hay envío WhatsApp | Dry-run solo simula; Cloud exige configuración, consentimiento, plantilla y confirmación |
| Aviso incierto | Revisa Meta antes de generar otro, para evitar duplicados |
| PostgreSQL no encuentra tablas | Asegura `currentSchema=licitagestion_private` y migraciones Flyway exitosas |

Los errores externos se presentan de forma controlada. Para problemas de BD revisa conectividad, migraciones y permisos del servidor; no muestres secretos ni SQL con documentos privados al usuario final.

