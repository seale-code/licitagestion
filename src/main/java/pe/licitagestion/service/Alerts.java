package pe.licitagestion.service;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import pe.licitagestion.persistence.Store;
import pe.licitagestion.security.Access;
import static pe.licitagestion.domain.Rules.*;
import static pe.licitagestion.persistence.Store.fields;

@Service
public class Alerts {
    private final Store db;
    private final Access access;
    private final Clock clock;

    public Alerts(Store db, Access access, Clock clock) {
        this.db = db;
        this.access = access;
        this.clock = clock;
    }

    public Map<String, Object> dashboard(Map<String, Object> user) {
        var tenders = access.visible(user);
        var alerts = new ArrayList<Map<String, Object>>();
        var dates = new ArrayList<Map<String, Object>>();
        var tenderIds = tenders.stream().map(t -> id(t.get("id"))).toList();
        var requirements = byTender("SELECT r.*, u.name AS owner_name, d.name AS document_name FROM requirement r JOIN app_user u ON r.owner_id=u.id LEFT JOIN document d ON r.document_id=d.id", "r", tenderIds);
        var tasks = byTender("SELECT t.*, u.name AS owner_name FROM task t JOIN app_user u ON t.owner_id=u.id", "t", tenderIds);
        var milestones = byTender("SELECT m.*, u.name AS owner_name FROM milestone m JOIN app_user u ON m.owner_id=u.id", "m", tenderIds);
        tasks.values().stream().flatMap(Collection::stream).forEach(t -> t.put("timing", timing(instant(t.get("deadline")), clock.instant(), "COMPLETADA".equals(t.get("status")))));
        int activeCount = 0, soon = 0, late = 0, pendingTasks = 0, missingDocs = 0, incomplete = 0;

        for (var tender : tenders) {
            long tid = id(tender.get("id"));
            boolean active = active(tender.get("status").toString());
            var reqs = requirements.getOrDefault(tid, List.of());
            var tenderTasks = tasks.getOrDefault(tid, List.of());
            var tenderMilestones = milestones.getOrDefault(tid, List.of());

            long total = reqs.size();
            long done = reqs.stream().filter(r -> Set.of("COMPLETADO", "NO_APLICA").contains(r.get("status"))).count();
            tender.put("requirements_total", total);
            tender.put("requirements_done", done);
            tender.put("timing", timing(instant(tender.get("deadline")), clock.instant(), !active));
            tender.put("progress", total == 0 ? 0 : Math.round(done * 100.0 / total));

            if (!active) continue;
            activeCount++;

            String timing = tender.get("timing").toString();
            if ("ATRASADA".equals(timing)) late++;
            if ("PROXIMA".equals(timing)) soon++;
            if (!"EN_PLAZO".equals(timing)) {
                alerts.add(alert("tender:" + tid, tender, "PROCESO", timing, "Presentación de propuesta", tender.get("owner_id"), tender.get("owner_name"), tender.get("deadline")));
            }
            dates.add(fields("id", "tender:" + tid, "tender_id", tid, "code", tender.get("code"), "name", "Presentación de propuesta", "deadline", tender.get("deadline"), "owner_name", tender.get("owner_name")));

            for (var req : reqs) {
                if (Set.of("COMPLETADO", "NO_APLICA").contains(req.get("status"))) continue;
                incomplete++;
                boolean missing = "DOCUMENTO".equals(req.get("kind")) && req.get("document_id") == null;
                if (missing) missingDocs++;
                alerts.add(alert("req:" + req.get("id"), tender, missing ? "DOCUMENTO_FALTANTE" : "REQUISITO", timing(instant(req.get("deadline")), clock.instant(), false), req.get("description"), req.get("owner_id"), req.get("owner_name"), req.get("deadline")));
            }

            for (var task : tenderTasks) {
                if ("COMPLETADA".equals(task.get("status"))) continue;
                pendingTasks++;
                if (!"EN_PLAZO".equals(task.get("timing"))) {
                    alerts.add(alert("task:" + task.get("id"), tender, "TAREA", task.get("timing"), task.get("name"), task.get("owner_id"), task.get("owner_name"), task.get("deadline")));
                }
            }

            for (var m : tenderMilestones) {
                if (Boolean.TRUE.equals(m.get("completed"))) continue;
                var date = new LinkedHashMap<>(m);
                date.put("code", tender.get("code"));
                dates.add(date);
                String mt = timing(instant(m.get("deadline")), clock.instant(), false);
                if (!"EN_PLAZO".equals(mt)) {
                    alerts.add(alert("date:" + m.get("id"), tender, "FECHA", mt, m.get("name"), m.get("owner_id"), m.get("owner_name"), m.get("deadline")));
                }
            }
        }

        alerts.sort(Comparator.<Map<String, Object>, Integer>comparing(a -> "ATRASADA".equals(a.get("timing")) ? 0 : 1).thenComparing(a -> instant(a.get("deadline"))));
        dates.sort(Comparator.comparing(a -> instant(a.get("deadline"))));

        return fields(
            "metrics", fields(
                "active", activeCount,
                "soon", soon,
                "late", late,
                "pendingTasks", pendingTasks,
                "missingDocuments", missingDocs,
                "incompleteRequirements", incomplete
            ),
            "tenders", tenders,
            "alerts", alerts,
            "dates", dates,
            "now", clock.instant()
        );
    }

    private Map<Long, List<Map<String, Object>>> byTender(String selectAndJoins, String alias, List<Long> tenderIds) {
        if (tenderIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(tenderIds.size(), "?"));
        return db.list(selectAndJoins + " WHERE " + alias + ".tender_id IN (" + placeholders + ") ORDER BY " + alias + ".deadline, " + alias + ".id", tenderIds.toArray())
            .stream().collect(java.util.stream.Collectors.groupingBy(row -> id(row.get("tender_id"))));
    }

    private Map<String, Object> alert(String key, Map<String, Object> tender, String kind, Object timing, Object title, Object owner, Object ownerName, Object deadline) {
        return fields(
            "key", key,
            "tender_id", tender.get("id"),
            "code", tender.get("code"),
            "kind", kind,
            "timing", timing,
            "title", title,
            "owner_id", owner,
            "owner_name", ownerName,
            "deadline", deadline
        );
    }
}

