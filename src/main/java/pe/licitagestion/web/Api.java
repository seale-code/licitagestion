package pe.licitagestion.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import pe.licitagestion.persistence.Store;
import pe.licitagestion.security.Access;
import pe.licitagestion.service.*;
import pe.licitagestion.web.Inputs.*;
import static pe.licitagestion.persistence.Store.fields;

@RestController
@RequestMapping("/api")
public class Api {
    private final Access access;
    private final Workflow workflow;
    private final Documents documents;
    private final Analysis analysis;
    private final Alerts alerts;
    private final Notifications notifications;
    private final Store db;
    private final String zone;

    public Api(
        Access access,
        Workflow workflow,
        Documents documents,
        Analysis analysis,
        Alerts alerts,
        Notifications notifications,
        Store db,
        @Value("${app.zone}") String zone
    ) {
        this.access = access;
        this.workflow = workflow;
        this.documents = documents;
        this.analysis = analysis;
        this.alerts = alerts;
        this.notifications = notifications;
        this.db = db;
        this.zone = zone;
    }

    @GetMapping("/session")
    public Object session(Authentication auth, CsrfToken csrf) {
        var u = access.user(auth);
        return fields(
            "user", fields("id", u.get("id"), "name", u.get("name"), "email", u.get("email"), "role", u.get("role")),
            "csrf", fields("token", csrf.getToken(), "header", csrf.getHeaderName(), "parameter", csrf.getParameterName()),
            "aiProvider", analysis.providerName(),
            "whatsappMode", notifications.mode(),
            "timezone", zone
        );
    }

    @GetMapping("/dashboard")
    public Object dashboard(Authentication auth) {
        return alerts.dashboard(access.user(auth));
    }

    @GetMapping("/tasks")
    public Object tasks(Authentication auth) {
        return workflow.allTasks(access.user(auth));
    }

    @GetMapping("/tenders")
    public Object tenders(
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String status,
        Authentication auth
    ) {
        if (page != null || size != null || q != null || status != null) {
            return access.visiblePage(access.user(auth), page == null ? 0 : page, size == null ? 25 : size, q, status);
        }
        return access.visible(access.user(auth), page, size);
    }

    @GetMapping("/tenders/{tid}")
    public Object tender(@PathVariable long tid, Authentication auth) {
        return workflow.detail(tid, access.user(auth));
    }

    @PostMapping("/tenders")
    public Object createTender(@Valid @RequestBody TenderInput in, Authentication auth) {
        return Map.of("id", workflow.saveTender(null, in, access.user(auth)));
    }

    @PutMapping("/tenders/{tid}")
    public Object updateTender(@PathVariable long tid, @Valid @RequestBody TenderInput in, Authentication auth) {
        return Map.of("id", workflow.saveTender(tid, in, access.user(auth)));
    }

    @GetMapping("/users")
    public Object users(Authentication auth) {
        return workflow.users(access.user(auth));
    }

    @PostMapping("/users")
    public Object createUser(@Valid @RequestBody UserInput in, Authentication auth) {
        return Map.of("id", workflow.saveUser(null, in, access.user(auth)));
    }

    @PutMapping("/users/{uid}")
    public Object updateUser(@PathVariable long uid, @Valid @RequestBody UserInput in, Authentication auth) {
        return Map.of("id", workflow.saveUser(uid, in, access.user(auth)));
    }

    @GetMapping("/audit")
    public Object audit(
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size,
        Authentication auth
    ) {
        access.admin(access.user(auth));
        String sql = "SELECT a.*, u.name AS actor_name FROM audit_event a LEFT JOIN app_user u ON a.actor_id=u.id ORDER BY a.id DESC";
        if (page != null || size != null) {
            int safePage = Math.max(0, page == null ? 0 : page), safeSize = Math.clamp(size == null ? 25 : size, 1, 100);
            long total = db.count("SELECT count(*) FROM audit_event");
            return fields("items", db.page(sql, safePage, safeSize), "page", safePage, "size", safeSize,
                "totalItems", total, "totalPages", (total + safeSize - 1) / safeSize);
        }
        return db.list(sql + " LIMIT 200");
    }

    @PostMapping("/tenders/{tid}/requirements")
    public Object addRequirement(@PathVariable long tid, @Valid @RequestBody RequirementInput in, Authentication auth) {
        return Map.of("id", workflow.saveRequirement(tid, null, in, access.user(auth)));
    }

    @PutMapping("/tenders/{tid}/requirements/{rid}")
    public Object updateRequirement(
        @PathVariable long tid,
        @PathVariable long rid,
        @Valid @RequestBody RequirementInput in,
        Authentication auth
    ) {
        return Map.of("id", workflow.saveRequirement(tid, rid, in, access.user(auth)));
    }

    @PostMapping("/tenders/{tid}/requirements/{rid}/task")
    public Object taskFromRequirement(@PathVariable long tid, @PathVariable long rid, Authentication auth) {
        return Map.of("id", workflow.convertTask(tid, rid, access.user(auth)));
    }

    @PostMapping("/tenders/{tid}/tasks")
    public Object addTask(@PathVariable long tid, @Valid @RequestBody TaskInput in, Authentication auth) {
        return Map.of("id", workflow.saveTask(tid, null, in, access.user(auth)));
    }

    @PutMapping("/tenders/{tid}/tasks/{taskId}")
    public Object updateTask(
        @PathVariable long tid,
        @PathVariable long taskId,
        @Valid @RequestBody TaskInput in,
        Authentication auth
    ) {
        return Map.of("id", workflow.saveTask(tid, taskId, in, access.user(auth)));
    }

    @PostMapping("/tenders/{tid}/milestones")
    public Object addMilestone(@PathVariable long tid, @Valid @RequestBody MilestoneInput in, Authentication auth) {
        return Map.of("id", workflow.saveMilestone(tid, null, in, access.user(auth)));
    }

    @PutMapping("/tenders/{tid}/milestones/{mid}")
    public Object updateMilestone(
        @PathVariable long tid,
        @PathVariable long mid,
        @Valid @RequestBody MilestoneInput in,
        Authentication auth
    ) {
        return Map.of("id", workflow.saveMilestone(tid, mid, in, access.user(auth)));
    }

    @PostMapping("/tenders/{tid}/documents")
    public Object upload(
        @PathVariable long tid,
        @RequestParam String kind,
        @RequestParam MultipartFile file,
        Authentication auth
    ) throws IOException {
        var actor = access.user(auth);
        long did = documents.upload(tid, kind, file, actor);
        String warning = "";
        try {
            analysis.analyze(tid, did, actor);
        } catch (RuntimeException e) {
            warning = "Documento guardado. El análisis no se completó; puedes reintentarlo desde Documentos.";
        }
        return Map.of("id", did, "warning", warning);
    }

    @GetMapping("/tenders/{tid}/documents/{did}/download")
    public ResponseEntity<FileSystemResource> download(
        @PathVariable long tid,
        @PathVariable long did,
        Authentication auth
    ) {
        var actor = access.user(auth);
        var doc = documents.get(tid, did, actor);
        var resource = new FileSystemResource(documents.path(doc));
        if (!resource.exists()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no disponible");
        }
        workflow.audit(actor, tid, "DOCUMENTO_DESCARGADO", "Documento #" + did);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(doc.get("mime").toString()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(doc.get("name").toString(), StandardCharsets.UTF_8).build().toString())
            .cacheControl(CacheControl.noStore())
            .body(resource);
    }

    @PostMapping("/tenders/{tid}/documents/{did}/analyze")
    public Object analyze(@PathVariable long tid, @PathVariable long did, Authentication auth) {
        return Map.of("id", analysis.analyze(tid, did, access.user(auth)));
    }

    @PostMapping("/tenders/{tid}/suggestions/{sid}/review")
    public Object review(
        @PathVariable long tid,
        @PathVariable long sid,
        @Valid @RequestBody Review in,
        Authentication auth
    ) {
        analysis.review(tid, sid, in, access.user(auth));
        return Map.of("ok", true);
    }

    @PostMapping("/tenders/{tid}/chat")
    public Object chat(@PathVariable long tid, @Valid @RequestBody Question in, Authentication auth) {
        return analysis.ask(tid, in.question(), access.user(auth));
    }

    @GetMapping("/notifications")
    public Object notifications(Authentication auth) {
        return notifications.list(access.user(auth));
    }

    @PostMapping("/notifications")
    public Object prepare(@Valid @RequestBody NotificationInput in, Authentication auth) {
        return Map.of("id", notifications.prepare(in.alertKey(), in.recipientId(), access.user(auth)));
    }

    @PostMapping("/notifications/{nid}/send")
    public Object send(@PathVariable long nid, Authentication auth) {
        notifications.send(nid, access.user(auth));
        return Map.of("ok", true);
    }
}

