# Mejora de calidad y preparación para producción

## Intención

Elevar la calidad de la entrega actual de LicitaGestión cerrando los riesgos detectados en la revisión, haciendo que la paginación declarada sea efectiva desde la interfaz y manteniendo la documentación fiel al comportamiento del código.

## Objetivos

- Evitar que un cliente falsifique `X-Forwarded-For` para evadir el límite de login.
- Habilitar el uso del encabezado reenviado únicamente desde direcciones de proxy configuradas explícitamente.
- Paginación navegable para procesos y auditoría, con conteos y estados vacíos claros.
- Corregir documentación que contradice la revocación de sesiones y aclarar límites de operación.
- Mantener las integraciones externas y el envío de mensajes desactivados por defecto.

## Fuera de alcance

Provisionar cloud/TLS, migrar almacenamiento a objetos, añadir MFA/OCR, o implementar una cola distribuida requieren infraestructura y decisiones de operación que este repositorio no puede tomar por sí solo.

## Enfoques considerados

1. Cambios solo en documentación: rápido, pero deja los riesgos y las pantallas sin resolver.
2. Correcciones de código, interfaz y documentación en el stack existente: elegido porque aborda el riesgo real y entrega un flujo usable sin introducir servicios externos.
3. Rediseño empresarial con Redis, OCR, almacenamiento cloud y SSO: mayor alcance y depende de infraestructura no configurada.

## Decisiones

- Proxy confiable: lista explícita de direcciones remotas permitidas; el encabezado reenviado se ignora para cualquier otro cliente.
- Paginación: conservar las respuestas antiguas cuando no se pasan parámetros, y devolver un sobre paginado cuando se solicita `page` y `size`.
- Páginas con tamaño acotado, controles anterior/siguiente y totales; búsqueda/filtro se aplica a la colección paginada obtenida.
- El dashboard carga requisitos, tareas e hitos en tres consultas agrupadas, manteniendo sus métricas y orden actuales.
- Mantener el limitador de login local en memoria y documentar que despliegues con varias réplicas necesitan almacenamiento compartido.

## Criterios de aceptación

- El `X-Forwarded-For` enviado por un cliente no confiable no modifica la clave usada por el limitador.
- Un proxy permitido puede reenviar una IP numérica válida; entradas malformadas no causan fallos.
- La pantalla de procesos y actividad puede recorrer todas sus páginas; API conserva compatibilidad con solicitudes sin paginación.
- Documentación sobre cambio de contraseña y sesiones coincide con `Access.checkSessionValidity`.
- Las pruebas existentes y nuevas pasan en el entorno H2; el estado de PostgreSQL real y servicios externos se informa por separado.

