package pe.licitagestion.service;

import java.util.*;
import java.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.licitagestion.ai.*;
import pe.licitagestion.ai.AssistantProvider.*;
import pe.licitagestion.persistence.Store;
import pe.licitagestion.security.Access;
import pe.licitagestion.web.Inputs.*;
import static pe.licitagestion.persistence.Store.fields;
import static pe.licitagestion.domain.Rules.*;

@Service
public class Analysis {
    private final Store db;
    private final Documents documents;
    private final Workflow workflow;
    private final Access access;
    private final AssistantProvider provider;
    private final ObjectMapper json;
    private final ZoneId zone;

    public Analysis(
        Store db,
        Documents docs,
        Workflow workflow,
        Access access,
        ExtractiveProvider local,
        LlmProvider llm,
        ObjectMapper json,
        @Value("${app.ai.mode}") String mode,
        @Value("${app.zone}") String zone
    ) {
        this.db = db;
        this.documents = docs;
        this.workflow = workflow;
        this.access = access;
        this.json = json;
        this.zone = ZoneId.of(zone);

        if (!Set.of("extractive", "llm").contains(mode)) {
            throw new IllegalArgumentException("AI_MODE debe ser extractive o llm");
        }
        this.provider = mode.equals("llm") ? llm : local;
    }

    public String providerName() {
        return provider.name();
    }

    private void limit(List<Source> sources) {
        if (sources.stream().mapToInt(s -> s.text().length()).sum() > 150000) {
            throw new IllegalArgumentException("El contexto supera 150 000 caracteres. Analiza documentos individualmente o reduce su tamaño.");
        }
    }

    private Source verify(List<Source> sources, String sourceId, String quote) {
        if (quote == null || quote.isBlank() || quote.length() > 2000) {
            throw new IllegalArgumentException("El proveedor devolvió una cita inválida");
        }
        return sources.stream()
            .filter(s -> s.id().equals(sourceId) && s.text().contains(quote))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("La cita del proveedor no coincide con una fuente disponible"));
    }

    public long analyze(long tid, long did, Map<String, Object> actor) {
        access.tender(tid, actor, true);
        var doc = documents.get(tid, did, actor);
        var sources = documents.sources(doc);
        limit(sources);

        // Check for existing cached analysis run first
        var old = db.optional(
            "SELECT id FROM analysis_run WHERE document_id=? AND provider=? ORDER BY id DESC LIMIT 1",
            did, provider.name()
        );
        if (old.isPresent()) {
            return id(old.get().get("id"));
        }

        // Call provider extraction outside active database transaction to avoid holding connection pool connections
        var output = sources.isEmpty()
            ? new Extraction("No se encontró texto extraíble. PDF escaneado: se requiere OCR o una versión con texto.", List.of())
            : provider.extract(sources);

        if (output == null || output.summary() == null || output.summary().isBlank()
            || output.summary().length() > 10000 || output.findings() == null || output.findings().size() > 40) {
            throw new IllegalArgumentException("El proveedor devolvió un análisis inválido");
        }
        for (var f : output.findings()) {
            if (f == null || f.description() == null || f.description().isBlank()
                || f.description().length() > 1500 || f.kind() == null) {
                throw new IllegalArgumentException("Sugerencia inválida, demasiado larga o vacía");
            }
            workflow.choice(f.kind(), Set.of("DOCUMENTO", "REQUISITO", "FECHA", "MONTO", "CONDICION", "EXPERIENCIA", "CRITERIO", "PENALIDAD"));
            verify(sources, f.sourceId(), f.quote());
        }

        return persistAnalysis(tid, did, actor, output);
    }

    @Transactional
    public long persistAnalysis(long tid, long did, Map<String, Object> actor, Extraction output) {
        // Under transaction lock, ensure repeated calls reuse the immutable document analysis
        db.one("SELECT id FROM document WHERE id=? FOR UPDATE", did);
        var existing = db.optional(
            "SELECT id FROM analysis_run WHERE document_id=? AND provider=? ORDER BY id DESC LIMIT 1",
            did, provider.name()
        );
        if (existing.isPresent()) {
            return id(existing.get().get("id"));
        }

        long run = db.insert("analysis_run", fields(
            "document_id", did,
            "provider", provider.name(),
            "summary", output.summary(),
            "created_by", id(actor.get("id"))
        ));
        for (var f : output.findings()) {
            db.insert("suggestion", fields(
                "analysis_id", run,
                "kind", f.kind(),
                "description", f.description(),
                "source_reference", f.sourceId(),
                "source_quote", f.quote()
            ));
        }
        workflow.audit(actor, tid, "ANALISIS_GENERADO", "Documento #" + did + "; " + output.findings().size() + " sugerencias pendientes de revisión");
        return run;
    }

    @Transactional
    public void review(long tid, long sid, Review in, Map<String, Object> actor) {
        access.tender(tid, actor, true);
        db.one("SELECT id FROM suggestion WHERE id=? FOR UPDATE", sid);
        var s = db.one(
            "SELECT s.*, a.document_id FROM suggestion s JOIN analysis_run a ON a.id=s.analysis_id JOIN document d ON d.id=a.document_id WHERE s.id=? AND d.tender_id=?",
            sid, tid
        );
        if (!"PENDIENTE".equals(s.get("status"))) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "La sugerencia ya fue revisada");
        }
        workflow.choice(in.action(), Set.of("ACEPTAR", "DESCARTAR"));
        if ("DESCARTAR".equals(in.action())) {
            db.update("UPDATE suggestion SET status='DESCARTADA' WHERE id=?", sid);
        } else if ("FECHA".equals(in.kind())) {
            long mid = workflow.saveMilestone(tid, null, new MilestoneInput(
                in.description().substring(0, Math.min(200, in.description().length())),
                "OTRA",
                in.deadline(),
                in.ownerId(),
                false,
                "Origen: " + s.get("source_reference") + ". Cita: " + s.get("source_quote"),
                0
            ), actor);
            db.update("UPDATE suggestion SET status='ACEPTADA', milestone_id=? WHERE id=?", mid, sid);
        } else {
            String type = REQUIREMENT_TYPES.contains(in.kind()) ? in.kind() : "OTRO";
            long rid = workflow.saveRequirement(tid, null, new RequirementInput(
                in.description(),
                type,
                "PENDIENTE",
                in.ownerId(),
                in.deadline(),
                null,
                "Fuente: " + s.get("source_reference") + ". La base es la fuente, no evidencia de cumplimiento.",
                0
            ), actor);
            db.update("UPDATE requirement SET source='ANALISIS_REVISADO', source_reference=? WHERE id=?", s.get("source_reference"), rid);
            db.update("UPDATE suggestion SET status='ACEPTADA', requirement_id=? WHERE id=?", rid, sid);
        }
        workflow.audit(actor, tid, "SUGERENCIA_REVISADA", "Sugerencia #" + sid + ": " + in.action());
    }

    public Answer ask(long tid, String question, Map<String, Object> actor) {
        var tender = access.tender(tid, actor, false);
        var sources = new ArrayList<>(documents.tenderSources(tid));
        var requirements = workflow.requirements(tid);
        var tasks = workflow.tasks(tid);

        long missingDocuments = requirements.stream()
            .filter(r -> "DOCUMENTO".equals(r.get("kind")) && !Set.of("COMPLETADO", "NO_APLICA").contains(r.get("status")) && r.get("document_id") == null)
            .count();
        sources.add(new Source("registro:resumen-documentos", "Resumen del checklist interno", "Documentos faltantes en el checklist registrado: " + missingDocuments + ". Este conteo no garantiza que se hayan identificado todas las exigencias de las bases."));
        sources.add(new Source("registro:resumen-tareas", "Resumen de tareas internas", "Tareas pendientes o en progreso registradas: " + tasks.stream().filter(t -> !"COMPLETADA".equals(t.get("status"))).count() + "."));
        sources.add(new Source("registro:licitacion:" + tid, "Ficha interna (registrada por usuarios)", "Licitación: " + tender.get("title") + ". Fecha límite de cierre: " + instant(tender.get("deadline")).atZone(zone) + ". Estado: " + tender.get("status") + ". Descripción: " + tender.get("description")));

        for (var r : requirements) {
            sources.add(new Source("registro:requisito:" + r.get("id"), "Checklist interno #" + r.get("id"), "Requisito: " + r.get("description") + ". Tipo: " + r.get("kind") + ". Estado: " + r.get("status") + ". Responsable: " + r.get("owner_name") + ". Fecha límite: " + instant(r.get("deadline")).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z")) + ". Documento vinculado: " + Objects.toString(r.get("document_name"), "DOCUMENTO".equals(r.get("kind")) ? "FALTANTE / no vinculado" : "No exigido como evidencia documental para este tipo")));
        }
        for (var t : tasks) {
            sources.add(new Source("registro:tarea:" + t.get("id"), "Tarea interna #" + t.get("id"), "Tarea: " + t.get("name") + ". Estado: " + t.get("status") + " (" + t.get("timing") + "). Responsable: " + t.get("owner_name") + ". Fecha límite: " + instant(t.get("deadline")).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z"))));
        }
        for (var m : workflow.milestones(tid)) {
            sources.add(new Source("registro:fecha:" + m.get("id"), "Agenda interna #" + m.get("id"), "Fecha importante: " + m.get("name") + ". Fecha límite: " + instant(m.get("deadline")).atZone(zone) + ". Responsable: " + m.get("owner_name") + ". Completada: " + m.get("completed")));
        }
        limit(sources);

        // Assistant call outside of transaction
        Answer output = provider.answer(question, sources);
        if (output == null || output.answer() == null || output.answer().isBlank()
            || output.answer().length() > 15000 || output.citations() == null || output.citations().size() > 8) {
            throw new IllegalArgumentException("Respuesta del asistente inválida");
        }

        var citations = new ArrayList<Citation>();
        for (var c : output.citations()) {
            if (c == null) throw new IllegalArgumentException("Cita del proveedor inválida");
            var source = verify(sources, c.sourceId(), c.quote());
            citations.add(new Citation(source.id(), source.label(), c.quote()));
        }

        String answer = citations.isEmpty()
            ? "No encuentro esa información en los documentos y registros disponibles de esta licitación. No puedo confirmarla."
            : output.answer();

        persistChatMessage(tid, question, actor, answer, citations);
        return new Answer(answer, citations);
    }

    @Transactional
    public void persistChatMessage(long tid, String question, Map<String, Object> actor, String answer, List<Citation> citations) {
        try {
            db.insert("chat_message", fields(
                "tender_id", tid,
                "user_id", id(actor.get("id")),
                "question", question,
                "answer", answer,
                "citations", json.writeValueAsString(citations),
                "provider", provider.name()
            ));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        workflow.audit(actor, tid, "CONSULTA_ASISTENTE", "Consulta realizada; proveedor " + provider.name());
    }
}

