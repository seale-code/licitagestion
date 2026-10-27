package pe.licitagestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import pe.licitagestion.persistence.Store;
import pe.licitagestion.security.Access;
import static pe.licitagestion.persistence.Store.fields;
import static pe.licitagestion.domain.Rules.*;

@Service
public class Notifications {
    private final Store db;
    private final Access access;
    private final Alerts alerts;
    private final Workflow workflow;
    private final ObjectMapper json;
    private final Clock clock;
    private final String mode;
    private final String token;
    private final String phoneId;
    private final String version;
    private final String template;
    private final String language;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public Notifications(
        Store db, Access access, Alerts alerts, Workflow workflow, ObjectMapper json, Clock clock,
        @Value("${app.whatsapp.mode}") String mode,
        @Value("${app.whatsapp.token}") String token,
        @Value("${app.whatsapp.phone-id}") String phoneId,
        @Value("${app.whatsapp.version}") String version,
        @Value("${app.whatsapp.template}") String template,
        @Value("${app.whatsapp.language}") String language
    ) {
        this.db = db;
        this.access = access;
        this.alerts = alerts;
        this.workflow = workflow;
        this.json = json;
        this.clock = clock;
        this.mode = mode;
        this.token = token;
        this.phoneId = phoneId;
        this.version = version;
        this.template = template;
        this.language = language;

        if (!Set.of("dry-run", "cloud").contains(mode)) {
            throw new IllegalArgumentException("Modo WhatsApp no reconocido: " + mode);
        }
        if ("cloud".equals(mode)) {
            if (token == null || token.isBlank()
                || phoneId == null || !phoneId.matches("[0-9]+")
                || version == null || !version.matches("v[0-9]+\\.[0-9]+")
                || template == null || !template.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("Configura las credenciales, versión y plantilla aprobada de WhatsApp Cloud API");
            }
        }
    }

    public String mode() {
        return mode;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> alert(String key, Map<String, Object> actor) {
        return ((List<Map<String, Object>>) alerts.dashboard(actor).get("alerts"))
            .stream()
            .filter(a -> key.equals(a.get("key")))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Esta alerta ya no está activa"));
    }

    private String body(Map<String, Object> a) {
        String text = "Licitación " + a.get("code") + " · " + a.get("kind") + " · " + a.get("timing")
            + ". " + a.get("title") + ". Vencimiento UTC: " + instant(a.get("deadline"))
            + ". Responsable: " + a.get("owner_name") + ". Revisa el sistema privado.";
        return text.substring(0, Math.min(1000, text.length()));
    }

    public long prepare(String key, long recipientId, Map<String, Object> actor) {
        access.admin(actor);
        var a = alert(key, actor);
        long tid = id(a.get("tender_id"));
        var recipient = db.one("SELECT id, role, phone, whatsapp_opt_in FROM app_user WHERE id=? AND enabled=TRUE", recipientId);
        // Validates recipient access to the tender
        access.tender(tid, recipient, false);

        if (!Boolean.TRUE.equals(recipient.get("whatsapp_opt_in")) || recipient.get("phone").toString().isBlank()) {
            throw new IllegalArgumentException("El destinatario debe tener número y consentimiento WhatsApp configurados");
        }

        String content = body(a);
        String fingerprint = Documents.hash(content + "|" + recipient.get("phone"));
        var old = db.optional(
            "SELECT id FROM notification WHERE alert_key=? AND recipient_id=? AND alert_fingerprint=?",
            key, recipientId, fingerprint
        );
        if (old.isPresent()) {
            return id(old.get().get("id"));
        }

        long nid = db.insert("notification", fields(
            "tender_id", tid,
            "recipient_id", recipientId,
            "alert_key", key,
            "alert_fingerprint", fingerprint,
            "phone", recipient.get("phone"),
            "body", content,
            "status", "BORRADOR"
        ));
        workflow.audit(actor, tid, "AVISO_PREPARADO", "Aviso #" + nid + " a usuario #" + recipientId);
        return nid;
    }

    public List<Map<String, Object>> list(Map<String, Object> actor) {
        access.admin(actor);
        return db.list("SELECT n.*, u.name AS recipient_name FROM notification n JOIN app_user u ON n.recipient_id=u.id ORDER BY n.id DESC LIMIT 100");
    }

    public void send(long nid, Map<String, Object> actor) {
        access.admin(actor);
        var n = db.one("SELECT * FROM notification WHERE id=?", nid);
        if (!"BORRADOR".equals(n.get("status"))) {
            throw new IllegalArgumentException("Este aviso ya fue procesado. No se reenvía automáticamente.");
        }

        var recipient = db.one("SELECT id, role, phone, whatsapp_opt_in FROM app_user WHERE id=? AND enabled=TRUE", n.get("recipient_id"));
        access.tender(id(n.get("tender_id")), recipient, false);

        if (!Boolean.TRUE.equals(recipient.get("whatsapp_opt_in")) || !recipient.get("phone").equals(n.get("phone"))) {
            throw new IllegalArgumentException("El destinatario o su consentimiento cambió; prepara un aviso nuevo");
        }

        var fresh = alert(n.get("alert_key").toString(), actor);
        if (!Documents.hash(body(fresh) + "|" + recipient.get("phone")).equals(n.get("alert_fingerprint"))) {
            throw new IllegalArgumentException("La alerta cambió; prepara y revisa un aviso nuevo");
        }

        if (mode.equals("cloud") && (token.isBlank() || !phoneId.matches("[0-9]+") || !version.matches("v[0-9]+\\.[0-9]+") || !template.matches("[a-z0-9_]+"))) {
            throw new IllegalArgumentException("Configura las credenciales, versión y plantilla aprobada de WhatsApp Cloud API");
        }

        if (db.update("UPDATE notification SET status='ENVIANDO' WHERE id=? AND status='BORRADOR'", nid) != 1) {
            throw new IllegalArgumentException("El aviso ya está siendo procesado");
        }

        if (mode.equals("dry-run")) {
            db.update("UPDATE notification SET status='SIMULADA', sent_at=? WHERE id=?", OffsetDateTime.now(clock), nid);
            workflow.audit(actor, id(n.get("tender_id")), "WHATSAPP_SIMULADO", "Aviso #" + nid + "; sin transmisión externa");
            return;
        }

        try {
            var payload = Map.of(
                "messaging_product", "whatsapp",
                "to", n.get("phone").toString().replace("+", ""),
                "type", "template",
                "template", Map.of(
                    "name", template,
                    "language", Map.of("code", language),
                    "components", List.of(Map.of(
                        "type", "body",
                        "parameters", List.of(Map.of("type", "text", "text", n.get("body")))
                    ))
                )
            );

            var request = HttpRequest.newBuilder(URI.create("https://graph.facebook.com/" + version + "/" + phoneId + "/messages"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    db.update("UPDATE notification SET status=? WHERE id=?", response.statusCode() >= 500 ? "INCIERTA" : "ERROR", nid);
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta no confirmó el envío. Revisa el estado antes de reintentar para evitar duplicados.");
                }
                byte[] bytes = stream.readNBytes(64001);
                if (bytes.length > 64000) throw new IllegalStateException();
                String external = json.readTree(bytes).path("messages").path(0).path("id").asText();
                if (external.isBlank() || external.length() > 200) throw new IllegalStateException();
                db.update("UPDATE notification SET status='ACEPTADA_META', external_id=?, sent_at=? WHERE id=?", external, OffsetDateTime.now(clock), nid);
                workflow.audit(actor, id(n.get("tender_id")), "WHATSAPP_ACEPTADO", "Aviso #" + nid + " aceptado por Meta; entrega no confirmada");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            db.update("UPDATE notification SET status='INCIERTA' WHERE id=?", nid);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Resultado de envío incierto. Verifica en Meta antes de generar otro aviso.");
        }
    }
}

