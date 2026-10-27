package pe.licitagestion.security;

import java.time.Instant;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import pe.licitagestion.persistence.Store;
import static pe.licitagestion.persistence.Store.fields;
import pe.licitagestion.domain.Rules;
import static pe.licitagestion.domain.Rules.*;

@Service
public class Access {
    private final Store store;

    public Access(Store store) {
        this.store = store;
    }

    public Map<String, Object> user(Authentication auth) {
        if (auth == null) {
            throw new AccessDeniedException("Inicia sesión");
        }
        var user = store.optional(
            "SELECT id, name, email, role, enabled, phone, whatsapp_opt_in, password_updated_at FROM app_user WHERE email=? AND enabled=TRUE",
            auth.getName()
        ).orElseThrow(() -> new AccessDeniedException("Cuenta no disponible"));

        checkSessionValidity(user);
        return user;
    }

    private void checkSessionValidity(Map<String, Object> user) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            var session = sra.getRequest().getSession(false);
            if (session != null) {
                var authTime = (Instant) session.getAttribute("auth_time");
                if (authTime != null && user.get("password_updated_at") != null) {
                    var pwdUpdated = Rules.instant(user.get("password_updated_at"));
                    if (pwdUpdated.isAfter(authTime)) {
                        session.invalidate();
                        throw new AccessDeniedException("La contraseña fue cambiada. Inicia sesión nuevamente");
                    }
                } else if (authTime == null) {
                    session.setAttribute("auth_time", Instant.now());
                }
            }
        }
    }

    public boolean isAdmin(Map<String, Object> user) {
        return "ADMIN".equals(user.get("role"));
    }

    public void admin(Map<String, Object> user) {
        if (!isAdmin(user)) {
            throw new AccessDeniedException("Solo administradores");
        }
    }

    public Map<String, Object> tender(long tenderId, Map<String, Object> user, boolean edit) {
        var tender = store.one("SELECT * FROM tender WHERE id=?", tenderId);
        if (!isAdmin(user) && store.count("SELECT count(*) FROM tender_access WHERE tender_id=? AND user_id=?", tenderId, id(user.get("id"))) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso no disponible");
        }
        if (edit && !isAdmin(user) && !"GESTOR".equals(user.get("role"))) {
            throw new AccessDeniedException("Acceso de consulta");
        }
        return tender;
    }

    public void assignee(long tenderId, long userId) {
        var target = store.one("SELECT id, role, enabled FROM app_user WHERE id=?", userId);
        if (!Boolean.TRUE.equals(target.get("enabled"))) {
            throw new IllegalArgumentException("El responsable está desactivado");
        }
        if (!"ADMIN".equals(target.get("role")) && (!"GESTOR".equals(target.get("role")) || store.count("SELECT count(*) FROM tender_access WHERE tender_id=? AND user_id=?", tenderId, userId) == 0)) {
            throw new IllegalArgumentException("El responsable debe ser administrador o gestor asignado a este proceso");
        }
    }

    public List<Map<String, Object>> visible(Map<String, Object> user) {
        return visible(user, null, null);
    }

    public List<Map<String, Object>> visible(Map<String, Object> user, Integer page, Integer size) {
        String filter = isAdmin(user) ? "" : " WHERE t.id IN (SELECT tender_id FROM tender_access WHERE user_id=?)";
        String sql = "SELECT t.*, u.name AS owner_name FROM tender t JOIN app_user u ON t.owner_id=u.id" + filter + " ORDER BY t.deadline, t.id";
        Object[] args = isAdmin(user) ? new Object[0] : new Object[]{id(user.get("id"))};
        if (page != null && size != null) {
            return store.page(sql, page, size, args);
        }
        return store.list(sql, args);
    }

    public Map<String, Object> visiblePage(Map<String, Object> user, int page, int size, String query, String status) {
        StringBuilder where = new StringBuilder(isAdmin(user) ? " WHERE 1=1" : " WHERE t.id IN (SELECT tender_id FROM tender_access WHERE user_id=?)");
        var args = new ArrayList<Object>();
        if (!isAdmin(user)) args.add(id(user.get("id")));
        if (query != null && !query.isBlank()) {
            where.append(" AND (LOWER(t.code) LIKE ? OR LOWER(t.title) LIKE ? OR LOWER(t.entity) LIKE ?)");
            String term = query.trim();
            if (term.length() > 120) term = term.substring(0, 120);
            String pattern = "%" + term.toLowerCase(Locale.ROOT) + "%";
            args.add(pattern); args.add(pattern); args.add(pattern);
        }
        if (status != null && !status.isBlank()) { where.append(" AND t.status=?"); args.add(status); }
        String from = " FROM tender t JOIN app_user u ON t.owner_id=u.id LEFT JOIN (SELECT tender_id, COUNT(*) AS requirements_total, SUM(CASE WHEN status IN ('COMPLETADO','NO_APLICA') THEN 1 ELSE 0 END) AS requirements_done FROM requirement GROUP BY tender_id) r ON r.tender_id=t.id" + where;
        String select = "SELECT t.*, u.name AS owner_name, COALESCE(r.requirements_total,0) AS requirements_total, COALESCE(r.requirements_done,0) AS requirements_done" + from + " ORDER BY t.deadline, t.id";
        int safePage = Math.max(0, page), safeSize = Math.clamp(size, 1, 100);
        long total = store.count("SELECT count(*)" + from, args.toArray());
        return fields("items", store.page(select, safePage, safeSize, args.toArray()), "page", safePage,
            "size", safeSize, "totalItems", total, "totalPages", (total + safeSize - 1) / safeSize);
    }

}

