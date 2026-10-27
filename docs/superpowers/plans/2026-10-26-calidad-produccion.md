# Calidad y preparación para producción Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Cerrar hallazgos de seguridad y usabilidad de la revisión y dejar documentación operativa consistente.

**Architecture:** Mantener Spring JDBC y el frontend sin framework. La API añadirá un sobre paginado al solicitar página/tamaño y conservará la respuesta existente en modo legado. El limitador seguirá siendo local, pero solo aceptará IP reenviada desde proxies permitidos.

**Tech Stack:** Java 25, Spring Boot, Spring JDBC, JUnit/MockMvc, HTML/CSS/JavaScript.

**Spec:** `docs/superpowers/specs/2026-10-26-calidad-produccion-design.md`

## Global Constraints

- No introducir llamadas externas ni dependencias de runtime.
- Mantener el tamaño de página entre 1 y 100.
- No confiar en encabezados reenviados salvo proxy explícitamente configurado.
- Preservar solicitudes API existentes sin parámetros de paginación.

## Review Focus

- `X-Forwarded-For` falsificado o malformado: no debe cambiar la IP observada ni provocar error.
- Proxy de confianza: usar solo la última IP válida reenviada.
- Página/tamaño extremos: limitar tamaño y evitar overflow en el offset.
- Dataset vacío y última página: controles deben representar correctamente que no hay más elementos.
- Cambio de clave: documentación debe describir la revocación al siguiente acceso API.

### Task 1: Resolver IP de cliente con proxies de confianza

**Files:** Modify `src/main/java/pe/licitagestion/security/SecurityConfig.java`, create `src/main/java/pe/licitagestion/security/ClientAddressResolver.java`, modify `src/main/resources/application.properties` and `.env.example`; Test `src/test/java/pe/licitagestion/security/ClientAddressResolverTest.java`.

- [x] Añadir pruebas del comportamiento de IP confiable/no confiable y encabezado malformado.
- [ ] Correr las pruebas para observar fallo antes del cambio (pendiente: entorno sin JDK/JAVA_HOME).
- [x] Añadir propiedad de lista de proxies y resolución defensiva de IP.
- [ ] Ejecutar las pruebas y suite completa (pendiente: entorno sin JDK/JAVA_HOME).

### Task 2: Paginación de extremo a extremo

**Files:** Modify `src/main/java/pe/licitagestion/persistence/Store.java`, `src/main/java/pe/licitagestion/security/Access.java`, `src/main/java/pe/licitagestion/web/Api.java`, `src/main/java/pe/licitagestion/service/Alerts.java`, `src/main/resources/static/app.js`, `src/main/resources/static/responsive.css`; Test `src/test/java/pe/licitagestion/WorkflowIntegrationTest.java`.

- [x] Añadir pruebas de contenido, total y límites de paginación en procesos y auditoría.
- [ ] Correr las pruebas para observar fallo antes del cambio (pendiente: entorno sin JDK/JAVA_HOME).
- [x] Implementar consultas de conteo y objeto de página con offset seguro.
- [x] Añadir controles accesibles de anterior/siguiente en las dos vistas, solicitando páginas al API.
- [x] Agrupar las consultas de requisitos, tareas e hitos del dashboard en consultas por conjunto.
- [ ] Ejecutar las pruebas y suite completa (pendiente: entorno sin JDK/JAVA_HOME).

### Task 3: Alinear operación y roadmap

**Files:** Modify `docs/operacion.md`, `docs/roadmap.md`, `docs/arquitectura.md`, `README.md`.

- [x] Corregir las afirmaciones sobre sesiones y cobertura de paginación.
- [x] Describir límites del rate limit en memoria, proxies y réplicas.
- [x] Separar con claridad funcionalidades implementadas de trabajo pendiente para producción.
- [x] Revisar enlaces y consistencia entre alcance, riesgos y roadmap.

