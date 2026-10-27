package pe.licitagestion.service;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import pe.licitagestion.persistence.Store;
import pe.licitagestion.security.Access;
import pe.licitagestion.web.Inputs.*;
import static pe.licitagestion.persistence.Store.fields;
import static pe.licitagestion.domain.Rules.*;

@Service
public class Workflow {
    private final Store db;
    private final Access access;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public Workflow(Store db, Access access, PasswordEncoder encoder, Clock clock) {
        this.db = db;
        this.access = access;
        this.encoder = encoder;
        this.clock = clock;
    }

    public void audit(Map<String, Object> actor, Long tenderId, String action, String detail) {
        db.insert("audit_event", fields(
            "actor_id", id(actor.get("id")),
            "tender_id", tenderId,
            "action", action,
            "detail", detail
        ));
    }

    public void choice(String value, Set<String> choices) {
        if (!choices.contains(value)) {
            throw new IllegalArgumentException("Opción no válida");
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private void changed(int rows) {
        if (rows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Otra persona modificó este registro. Actualiza la pantalla antes de guardar.");
        }
    }

    public List<Map<String, Object>> users(Map<String, Object> actor) {
        access.admin(actor);
        return db.list("SELECT id, name, email, role, enabled, phone, whatsapp_opt_in FROM app_user ORDER BY name");
    }

    public List<Map<String, Object>> team(long tenderId) {
        return db.list(
            "SELECT id, name, email, role, enabled FROM app_user WHERE role='ADMIN' OR id IN (SELECT user_id FROM tender_access WHERE tender_id=?) ORDER BY name",
            tenderId
        );
    }

    @Transactional
    public long saveUser(Long userId, UserInput in, Map<String, Object> actor) {
        access.admin(actor);
        choice(in.role(), Set.of("ADMIN", "GESTOR", "CONSULTA"));

        if (in.whatsappOptIn() && (in.phone() == null || in.phone().isBlank())) {
            throw new IllegalArgumentException("Configura un teléfono para habilitar WhatsApp");
        }

        if (userId != null) {
            db.list("SELECT id FROM app_user WHERE role='ADMIN' ORDER BY id FOR UPDATE");
            var old = db.one("SELECT id, role, enabled FROM app_user WHERE id=?", userId);

            if ("ADMIN".equals(old.get("role")) && (!in.enabled() || !"ADMIN".equals(in.role()))
                && db.count("SELECT count(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE") <= 1) {
                throw new IllegalArgumentException("Debe quedar al menos un administrador activo");
            }

            if ((!in.role().equals(old.get("role"))) && !"ADMIN".equals(in.role()) && !"GESTOR".equals(in.role())
                && db.count("SELECT count(*) FROM tender WHERE owner_id=?", userId) > 0) {
                throw new IllegalArgumentException("Reasigna primero los procesos de este responsable");
            }

            if ("CONSULTA".equals(in.role()) && !in.role().equals(old.get("role"))) {
                for (String table : List.of("requirement", "task", "milestone")) {
                    if (db.count("SELECT count(*) FROM " + table + " WHERE owner_id=?", userId) > 0) {
                        throw new IllegalArgumentException("Reasigna primero los requisitos, tareas y fechas de este responsable");
                    }
                }
            }

            db.update(
                "UPDATE app_user SET name=?, email=?, role=?, enabled=?, phone=?, whatsapp_opt_in=? WHERE id=?",
                text(in.name()), text(in.email()).toLowerCase(), in.role(), in.enabled(), text(in.phone()), in.whatsappOptIn(), userId
            );

            if (in.password() != null && !in.password().isBlank()) {
                password(in.password());
                db.update(
                    "UPDATE app_user SET password_hash=?, password_updated_at=? WHERE id=?",
                    encoder.encode(in.password()), OffsetDateTime.now(clock), userId
                );
            }
        } else {
            password(in.password());
            userId = db.insert("app_user", fields(
                "name", text(in.name()),
                "email", text(in.email()).toLowerCase(),
                "role", in.role(),
                "enabled", in.enabled(),
                "phone", text(in.phone()),
                "whatsapp_opt_in", in.whatsappOptIn(),
                "password_hash", encoder.encode(in.password())
            ));
        }

        audit(actor, null, "USUARIO_GUARDADO", "Usuario #" + userId);
        return userId;
    }

    private void password(String password) {
        if (password == null || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 12 caracteres y como máximo 72 bytes UTF-8");
        }
    }

    @Transactional
    public long saveTender(Long tenderId, TenderInput in, Map<String, Object> actor) {
        choice(in.status(), STATUSES);
        choice(in.priority(), PRIORITIES);

        if (in.deadline().toLocalDate().isBefore(in.publishedOn())) {
            throw new IllegalArgumentException("El cierre no puede ser anterior a la publicación");
        }

        var owner = db.one("SELECT role, enabled FROM app_user WHERE id=?", in.ownerId());
        if (!Boolean.TRUE.equals(owner.get("enabled")) || "CONSULTA".equals(owner.get("role"))) {
            throw new IllegalArgumentException("Selecciona un responsable activo con permiso de gestión");
        }

        var assigned = new HashSet<>(in.accessIds());
        assigned.add(in.ownerId());
        for (Long uid : assigned) {
            if (uid == null || db.count("SELECT count(*) FROM app_user WHERE id=? AND enabled=TRUE", uid) != 1) {
                throw new IllegalArgumentException("Usuario asignado inválido");
            }
        }

        if (tenderId == null) {
            access.admin(actor);
            tenderId = db.insert("tender", fields(
                "code", text(in.code()),
                "title", text(in.title()),
                "entity", text(in.entity()),
                "description", text(in.description()),
                "published_on", in.publishedOn(),
                "deadline", in.deadline(),
                "status", in.status(),
                "priority", in.priority(),
                "owner_id", in.ownerId(),
                "notes", text(in.notes())
            ));
        } else {
            var old = access.tender(tenderId, actor, true);
            if (!access.isAdmin(actor)) {
                var original = new HashSet<>(db.list("SELECT user_id FROM tender_access WHERE tender_id=?", tenderId)
                    .stream().map(r -> id(r.get("user_id"))).toList());
                if (id(old.get("owner_id")) != in.ownerId().longValue() || !assigned.equals(original)) {
                    throw new AccessDeniedException("Solo el administrador puede cambiar los accesos");
                }
            }

            var owners = new HashSet<Long>();
            for (String table : List.of("requirement", "task", "milestone")) {
                db.list("SELECT DISTINCT owner_id FROM " + table + " WHERE tender_id=?", tenderId)
                    .forEach(r -> owners.add(id(r.get("owner_id"))));
            }
            for (Long uid : owners) {
                if (!assigned.contains(uid) && db.count("SELECT count(*) FROM app_user WHERE id=? AND role='ADMIN'", uid) == 0) {
                    throw new IllegalArgumentException("Reasigna tareas, requisitos y fechas antes de retirar acceso al responsable #" + uid);
                }
            }
            if ("LISTA_PARA_PRESENTAR".equals(in.status())
                && db.count("SELECT count(*) FROM requirement WHERE tender_id=? AND status NOT IN ('COMPLETADO','NO_APLICA')", tenderId) > 0) {
                throw new IllegalArgumentException("Completa o marca no aplicables los requisitos antes de dejar la propuesta lista");
            }

            changed(db.update(
                "UPDATE tender SET code=?, title=?, entity=?, description=?, published_on=?, deadline=?, status=?, priority=?, owner_id=?, notes=?, version=version+1 WHERE id=? AND version=?",
                text(in.code()), text(in.title()), text(in.entity()), text(in.description()), in.publishedOn(), in.deadline(), in.status(), in.priority(), in.ownerId(), text(in.notes()), tenderId, in.version()
            ));
            db.update("DELETE FROM tender_access WHERE tender_id=?", tenderId);
        }

        for (Long uid : assigned) {
            db.update("INSERT INTO tender_access(tender_id, user_id) VALUES (?, ?)", tenderId, uid);
        }
        audit(actor, tenderId, "PROCESO_GUARDADO", "Estado: " + in.status());
        return tenderId;
    }

    public Map<String, Object> detail(long tid, Map<String, Object> actor) {
        var t = access.tender(tid, actor, false);
        var result = new LinkedHashMap<String, Object>();
        result.put("tender", t);
        result.put("canEdit", access.isAdmin(actor) || "GESTOR".equals(actor.get("role")));
        result.put("team", team(tid));
        result.put("accessIds", db.list("SELECT user_id FROM tender_access WHERE tender_id=?", tid).stream().map(r -> r.get("user_id")).toList());
        result.put("documents", db.list("SELECT id, name, kind, mime, size_bytes, extraction_status, created_at FROM document WHERE tender_id=? ORDER BY id DESC", tid));
        result.put("requirements", requirements(tid));
        result.put("tasks", tasks(tid));
        result.put("milestones", milestones(tid));
        result.put("suggestions", db.list(
            "SELECT s.*, a.document_id, a.provider, d.name AS document_name FROM suggestion s JOIN analysis_run a ON s.analysis_id=a.id JOIN document d ON a.document_id=d.id WHERE d.tender_id=? ORDER BY s.id DESC",
            tid
        ));
        result.put("analyses", db.list(
            "SELECT a.*, d.name AS document_name FROM analysis_run a JOIN document d ON a.document_id=d.id WHERE d.tender_id=? ORDER BY a.id DESC",
            tid
        ));
        result.put("chat", db.list(
            "SELECT c.*, u.name AS user_name FROM chat_message c JOIN app_user u ON c.user_id=u.id WHERE tender_id=? ORDER BY c.id DESC LIMIT 30",
            tid
        ));
        result.put("audit", db.list(
            "SELECT a.*, u.name AS actor_name FROM audit_event a LEFT JOIN app_user u ON a.actor_id=u.id WHERE tender_id=? ORDER BY a.id DESC LIMIT 60",
            tid
        ));
        return result;
    }

    public List<Map<String, Object>> requirements(long tid) {
        return db.list(
            "SELECT r.*, u.name AS owner_name, d.name AS document_name FROM requirement r JOIN app_user u ON r.owner_id=u.id LEFT JOIN document d ON r.document_id=d.id WHERE r.tender_id=? ORDER BY r.deadline, r.id",
            tid
        );
    }

    public List<Map<String, Object>> tasks(long tid) {
        var rows = db.list(
            "SELECT t.*, u.name AS owner_name FROM task t JOIN app_user u ON t.owner_id=u.id WHERE tender_id=? ORDER BY deadline, t.id",
            tid
        );
        rows.forEach(t -> t.put("timing", timing(instant(t.get("deadline")), clock.instant(), "COMPLETADA".equals(t.get("status")))));
        return rows;
    }

    public List<Map<String, Object>> allTasks(Map<String, Object> actor) {
        String filter = access.isAdmin(actor) ? "" : " WHERE t.tender_id IN (SELECT tender_id FROM tender_access WHERE user_id=?)";
        String sql = "SELECT t.*, u.name AS owner_name, te.code FROM task t JOIN app_user u ON t.owner_id=u.id JOIN tender te ON t.tender_id=te.id" + filter + " ORDER BY t.deadline, t.id";
        var rows = access.isAdmin(actor) ? db.list(sql) : db.list(sql, id(actor.get("id")));
        rows.forEach(t -> t.put("timing", timing(instant(t.get("deadline")), clock.instant(), "COMPLETADA".equals(t.get("status")))));
        return rows;
    }

    public List<Map<String, Object>> milestones(long tid) {
        return db.list(
            "SELECT m.*, u.name AS owner_name FROM milestone m JOIN app_user u ON m.owner_id=u.id WHERE tender_id=? ORDER BY deadline, m.id",
            tid
        );
    }

    @Transactional
    public long saveRequirement(long tid, Long rid, RequirementInput in, Map<String, Object> actor) {
        access.tender(tid, actor, true);
        access.assignee(tid, in.ownerId());
        choice(in.kind(), REQUIREMENT_TYPES);
        choice(in.status(), Set.of("PENDIENTE", "EN_PROGRESO", "COMPLETADO", "NO_APLICA"));

        if (in.documentId() != null) {
            db.one("SELECT id FROM document WHERE id=? AND tender_id=?", in.documentId(), tid);
        }
        if ("DOCUMENTO".equals(in.kind()) && "COMPLETADO".equals(in.status()) && in.documentId() == null) {
            throw new IllegalArgumentException("Vincula el documento antes de completar este requisito documental");
        }

        if (rid == null) {
            rid = db.insert("requirement", fields(
                "tender_id", tid,
                "description", text(in.description()),
                "kind", in.kind(),
                "status", in.status(),
                "owner_id", in.ownerId(),
                "deadline", in.deadline(),
                "document_id", in.documentId(),
                "notes", text(in.notes())
            ));
        } else {
            db.one("SELECT id FROM requirement WHERE id=? AND tender_id=?", rid, tid);
            changed(db.update(
                "UPDATE requirement SET description=?, kind=?, status=?, owner_id=?, deadline=?, document_id=?, notes=?, version=version+1 WHERE id=? AND tender_id=? AND version=?",
                text(in.description()), in.kind(), in.status(), in.ownerId(), in.deadline(), in.documentId(), text(in.notes()), rid, tid, in.version()
            ));
        }

        audit(actor, tid, "REQUISITO_GUARDADO", "Requisito #" + rid + ": " + in.status());
        return rid;
    }

    @Transactional
    public long saveTask(long tid, Long taskId, TaskInput in, Map<String, Object> actor) {
        access.tender(tid, actor, true);
        access.assignee(tid, in.ownerId());
        choice(in.priority(), PRIORITIES);
        choice(in.status(), Set.of("PENDIENTE", "EN_PROGRESO", "COMPLETADA"));

        if (taskId == null) {
            taskId = db.insert("task", fields(
                "tender_id", tid,
                "name", text(in.name()),
                "description", text(in.description()),
                "owner_id", in.ownerId(),
                "deadline", in.deadline(),
                "priority", in.priority(),
                "status", in.status(),
                "notes", text(in.notes())
            ));
        } else {
            db.one("SELECT id FROM task WHERE id=? AND tender_id=?", taskId, tid);
            changed(db.update(
                "UPDATE task SET name=?, description=?, owner_id=?, deadline=?, priority=?, status=?, notes=?, version=version+1 WHERE id=? AND tender_id=? AND version=?",
                text(in.name()), text(in.description()), in.ownerId(), in.deadline(), in.priority(), in.status(), text(in.notes()), taskId, tid, in.version()
            ));
        }

        audit(actor, tid, "TAREA_GUARDADA", "Tarea #" + taskId + ": " + in.status());
        return taskId;
    }

    @Transactional
    public long convertTask(long tid, long rid, Map<String, Object> actor) {
        access.tender(tid, actor, true);
        var req = db.one("SELECT * FROM requirement WHERE id=? AND tender_id=?", rid, tid);
        var existing = db.optional("SELECT id FROM task WHERE requirement_id=?", rid);
        if (existing.isPresent()) {
            return id(existing.get().get("id"));
        }

        String desc = req.get("description").toString();
        long taskId = db.insert("task", fields(
            "tender_id", tid,
            "requirement_id", rid,
            "name", desc.substring(0, Math.min(200, desc.length())),
            "description", desc,
            "owner_id", req.get("owner_id"),
            "deadline", req.get("deadline"),
            "priority", "MEDIA",
            "status", "PENDIENTE",
            "notes", "Tarea vinculada: completar esta tarea no marca el requisito automáticamente."
        ));

        audit(actor, tid, "REQUISITO_A_TAREA", "Requisito #" + rid + " → tarea #" + taskId);
        return taskId;
    }

    @Transactional
    public long saveMilestone(long tid, Long mid, MilestoneInput in, Map<String, Object> actor) {
        access.tender(tid, actor, true);
        access.assignee(tid, in.ownerId());
        choice(in.kind(), MILESTONE_TYPES);

        if (mid == null) {
            mid = db.insert("milestone", fields(
                "tender_id", tid,
                "name", text(in.name()),
                "kind", in.kind(),
                "deadline", in.deadline(),
                "owner_id", in.ownerId(),
                "completed", in.completed(),
                "notes", text(in.notes())
            ));
        } else {
            db.one("SELECT id FROM milestone WHERE id=? AND tender_id=?", mid, tid);
            changed(db.update(
                "UPDATE milestone SET name=?, kind=?, deadline=?, owner_id=?, completed=?, notes=?, version=version+1 WHERE id=? AND tender_id=? AND version=?",
                text(in.name()), in.kind(), in.deadline(), in.ownerId(), in.completed(), text(in.notes()), mid, tid, in.version()
            ));
        }

        audit(actor, tid, "FECHA_GUARDADA", "Hito #" + mid);
        return mid;
    }
}

