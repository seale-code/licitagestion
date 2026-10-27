# Arquitectura del sistema privado

## Componentes

```text
Navegador (HTML/CSS/JS)
    │ sesión + token CSRF
    ▼
Spring Security → API REST → Validación de DTOs
                               │
                  ┌────────────┼───────────────┐
                  ▼            ▼               ▼
              Workflow      Documents      Analysis
           procesos/tareas  PDFBox + disco   AssistantProvider
                  │            │           ├─ ExtractiveProvider
                  │            │           └─ LlmProvider (opcional)
                  └────────────┼───────────────┘
                               ▼
                      Store / Spring JDBC
                               ▼
                 PostgreSQL + migraciones Flyway

Alerts → dashboard/agenda
       → Notifications → simulación / WhatsApp Cloud API
```

`web/Inputs` define contratos de entrada validados con Jakarta Validation. `security/Access` centraliza permisos y valida el acceso vigente en la base, además de la sesión Spring. `service/Workflow` agrupa las transacciones del dominio. `persistence/Store` usa parámetros JDBC; los nombres de tabla dinámicos provienen solo de listas internas, nunca del navegador. `domain/Rules` contiene reglas puras de fechas/estados.

Es un monolito modular por capas, no microservicios ni arquitectura hexagonal completa. El uso de mapas para las proyecciones JDBC simplifica este MVP, a costa de menor comprobación estática en lecturas; una evolución razonable es reemplazarlos por records de consulta. Los puertos de IA permiten cambiar proveedor sin cambiar el flujo de revisión.

## Datos

| Tabla | Propósito |
|---|---|
| `app_user` | Usuarios, hash, rol, habilitación, teléfono y consentimiento |
| `tender` | Ficha del proceso, estado, fechas, prioridad, responsable y versión |
| `tender_access` | Usuarios autorizados para cada proceso |
| `document` | Metadatos, clave privada, hash, texto por página y estado de extracción |
| `requirement` | Checklist, responsable, fecha, estado, evidencia y procedencia |
| `task` | Actividades; relación opcional única a requisito |
| `milestone` | Consultas, entregas, hitos internos y cumplimiento |
| `analysis_run` | Resultado y proveedor del análisis de un documento |
| `suggestion` | Hallazgo con cita, decisión humana y registro generado |
| `chat_message` | Consulta, respuesta y citas dentro de un proceso |
| `audit_event` | Acciones relevantes, actor y proceso |
| `notification` | Borradores/envíos WhatsApp, destinatario, huella y estado |

Flyway usa su propia tabla de historial además de las 12 tablas de aplicación. PostgreSQL guarda los timestamps con zona (`TIMESTAMP WITH TIME ZONE`); los cierres se comparan como instantes. La interfaz muestra la zona de la empresa y los formularios indican cuando capturan hora local del navegador.

La solución es para **una empresa por instalación**. Las personas comparten un catálogo privado según asignación. No hay aislamiento multiempresa ni miembros externos invitados por enlace. Si se transforma en SaaS, añade `company_id` a toda entidad y comprueba su alcance en cada consulta/índice/relación; no basta ocultar un menú.

## Matriz de permisos

| Acción | Administrador | Gestor | Consulta |
|---|---|---|---|
| Ver procesos/documentos | Todos | Asignados | Asignados |
| Crear proceso | Sí | No | No |
| Editar ficha | Sí | Asignados | No |
| Cambiar responsables principales y accesos | Sí | No | No |
| Gestionar checklist/tareas/fechas/documentos | Sí | Asignados | No |
| Revisar análisis | Sí | Asignados | No |
| Consultar asistente | Sí | Asignados | Asignados |
| Administrar usuarios y WhatsApp | Sí | No | No |

Un administrador es visible como responsable disponible en todo proceso. Un gestor responsable de una tarea/requisito debe estar asignado al proceso. No se permite vincular evidencia de otro proceso. Un usuario desactivado pierde acceso a la API incluso si tenía una sesión abierta. Los roles se leen en cada operación, por lo que una modificación de permisos se aplica sin esperar a que la sesión venza.

## Documento → checklist

1. Autenticar, comprobar CSRF y autorización de edición.
2. Validar tamaño, extensión y contenido. PDFBox carga el archivo; se rechazan PDFs dañados, cifrados o fuera de límites.
3. Guardar el binario con nombre UUID fuera del webroot, y el texto por páginas en la BD. El rollback elimina el archivo recién escrito si no se confirma la transacción.
4. Ejecutar el análisis; si falla, el documento permanece guardado y la UI permite reintentar.
5. Validar estructura y citas literales del resultado, y persistir sugerencias pendientes.
6. El usuario revisa/corrige/descarta. Aceptar crea requisito o hito en una transacción y marca la sugerencia como revisada.
7. El documento fuente queda citado, pero no se asigna automáticamente como evidencia de cumplimiento.

La descarga es `attachment` mediante ruta autenticada. No existe una URL pública que permita saltarse `Access`. Los archivos se conservan inmutables; las adendas se suben aparte. El parser se ejecuta sin OCR y no incorpora un antivirus. Para producción sensible debe moverse a un entorno aislado con límites de CPU/memoria y análisis del archivo.

## Asistente acotado

Las fuentes incluyen exclusivamente documentos y datos internos del proceso autorizado. Las fuentes documentales tienen identificadores `doc:ID:p:PAGINA`; los registros usan `registro:tipo:ID`. No se permite al modelo invocar herramientas, SQL, búsquedas o rutas.

Las citas del LLM se validan contra el conjunto de fuentes seleccionado por el backend, y la etiqueta se reconstruye desde ese conjunto. Un modelo no puede inventar el identificador de otro documento para acceder a él. Aun así, una cita existente no prueba corrección semántica; la revisión humana permanece necesaria. Si no hay citas válidas/suficientes se rechaza la respuesta o se informa que no hay información confirmable.

El límite de contexto produce un error explícito; no se trunca silenciosamente un expediente grande. Para escalar, segmenta y recupera pasajes relevantes con trazabilidad a páginas, luego evalúa calidad y cobertura con preguntas reales.

## Concurrencia y trazabilidad

- Versiones incrementales en proceso, requisito, tarea e hito detectan sobrescrituras (HTTP 409).
- La lista de procesos y la auditoría ofrecen páginas acotadas; las llamadas heredadas sin parámetros mantienen su formato anterior.
- Revisión de sugerencia y análisis de documento usan bloqueo de fila.
- Relación única entre requisito y tarea evita tareas duplicadas.
- Preparación de un aviso usa clave/huella/destinatario únicos. El envío reclama su estado con una actualización condicional.
- Los fallos ambiguos de red se marcan inciertos; reenviar automáticamente podría duplicar un mensaje.
- Auditoría cubre inicialización, usuarios, cambios de proceso/checklist/tareas/hitos, cargas/descargas, análisis, consultas y avisos. No es un log criptográficamente inmutable ni registra todavía todos los intentos de autenticación.

## Evolución a mayor escala

1. **Consultas y UI:** la lista de procesos ya pagina y filtra por código/nombre/entidad/estado; auditoría pagina resultados. El dashboard agrupa sus consultas de requisitos, tareas e hitos, pero todavía carga todos los procesos visibles y todos sus registros. Evolucionar a consultas por rangos/estadísticas agregadas, proyecciones tipadas e índices guiados por mediciones cuando aumente el volumen.
2. **Trabajos:** tabla de jobs con estado/reintentos/huella, outbox transaccional, cola Redis/RabbitMQ y workers de extracción/LLM. La UI consultaría el estado; evitar mantener bloqueos de BD durante llamadas largas.
3. **Documentos:** puerto `DocumentStorage` con implementación de Supabase Storage privado o S3. Mantener autorización en backend, archivos cifrados y políticas de retención. No activar buckets públicos.
4. **Avisos automáticos:** generar outbox por responsable y política de aviso, deduplicar por ventana temporal, respetar consentimiento; worker para Cloud API y webhook verificado para estados de entrega. El envío manual actual permite revisar el resultado antes de automatizarlo.
5. **Cloud:** contenedor web tras proxy HTTPS, PostgreSQL administrado, volumen/objeto privado y gestores de secretos. Sesiones distribuidas con Spring Session si hay varias réplicas.
6. **Calidad:** CI con pruebas PostgreSQL reales, pruebas de permisos contra cada ruta, límites de tasa, auditoría de dependencias, backups con restauración ensayada y observabilidad sin contenido privado.

La configuración entregada facilita estas incorporaciones; no significa que todos esos componentes ya estén desplegados.

