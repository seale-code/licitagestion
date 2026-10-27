package pe.licitagestion.domain;

import java.time.*;
import java.util.*;

public final class Rules {
    private Rules() {}
    public static final Set<String> STATUSES = Set.of("RECIBIDA","EN_REVISION","EN_PREPARACION","PENDIENTE_DOCUMENTOS","LISTA_PARA_PRESENTAR","PRESENTADA","ADJUDICADA","NO_ADJUDICADA","CANCELADA");
    public static final Set<String> FINAL = Set.of("PRESENTADA","ADJUDICADA","NO_ADJUDICADA","CANCELADA");
    public static final Set<String> PRIORITIES = Set.of("ALTA","MEDIA","BAJA");
    public static final Set<String> REQUIREMENT_TYPES = Set.of("DOCUMENTO","LEGAL","TECNICO","ECONOMICO","EXPERIENCIA","OTRO");
    public static final Set<String> DOCUMENT_TYPES = Set.of("BASES","TERMINOS_REFERENCIA","ANEXO","DECLARACION","CERTIFICADO","PROPUESTA","OTRO");
    public static final Set<String> MILESTONE_TYPES = Set.of("CONSULTAS","ENTREGA_DOCUMENTOS","INTERNA","PRESENTACION","OTRA");
    public static Instant instant(Object value) {
        if (value instanceof OffsetDateTime d) return d.toInstant();
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        if (value instanceof Instant i) return i;
        String s = value.toString();
        if (s.contains(" ") && s.contains("+")) s = s.replace(' ', 'T');
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception ignored) {
            return Instant.parse(value.toString());
        }
    }
    public static long id(Object value) { return ((Number)value).longValue(); }
    public static String timing(Instant deadline, Instant now, boolean done) {
        if (done) return "COMPLETADA";
        if (deadline.isBefore(now)) return "ATRASADA";
        return deadline.isAfter(now.plus(Duration.ofDays(3))) ? "EN_PLAZO" : "PROXIMA";
    }
    public static boolean active(String status) { return !FINAL.contains(status); }
}

