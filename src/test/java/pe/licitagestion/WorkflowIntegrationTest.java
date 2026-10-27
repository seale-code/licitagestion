package pe.licitagestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.io.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import pe.licitagestion.persistence.Store;
import pe.licitagestion.service.*;
import pe.licitagestion.web.Inputs.*;
import static pe.licitagestion.persistence.Store.fields;
import static pe.licitagestion.domain.Rules.id;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.profiles.active=test","spring.datasource.url=${TEST_DATABASE_URL:jdbc:h2:mem:workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${TEST_DATABASE_USER:sa}","spring.datasource.password=${TEST_DATABASE_PASSWORD:}","spring.flyway.schemas=${TEST_DATABASE_SCHEMA:public}","spring.flyway.default-schema=${TEST_DATABASE_SCHEMA:public}","app.bootstrap.email=admin@example.com","app.bootstrap.password=TestPassword2026!","app.demo-seed=false","app.storage=./target/test-private-documents","app.ai.mode=extractive","app.whatsapp.mode=dry-run"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class WorkflowIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean pe.licitagestion.ai.ExtractiveProvider localProvider;
    @Autowired MockMvc mvc;@Autowired Store db;@Autowired Workflow flow;@Autowired Documents documents;@Autowired Analysis analysis;@Autowired Alerts alerts;@Autowired Notifications notifications;@Autowired ObjectMapper json;@Autowired PasswordEncoder encoder;
    Map<String,Object> admin;long manager,viewer,other,tid;OffsetDateTime deadline;
    @BeforeEach void data(){
        admin=db.one("SELECT * FROM app_user WHERE email='admin@example.com'");
        String hash=admin.get("password_hash").toString();
        manager=db.insert("app_user",fields("name","Ana","email","gestor@example.com","password_hash",hash,"role","GESTOR"));
        viewer=db.insert("app_user",fields("name","Consulta","email","consulta@example.com","password_hash",hash,"role","CONSULTA"));
        other=db.insert("app_user",fields("name","Otro","email","otro@example.com","password_hash",hash,"role","GESTOR"));
        deadline=OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
        tid=flow.saveTender(null,tender("EN_PREPARACION",0),admin);
    }
    TenderInput tender(String status,int version){return new TenderInput("TEST-001","Proceso privado","Entidad Demo","Descripción",deadline.minusDays(5).toLocalDate(),deadline,status,"ALTA",manager,"",List.of(manager,viewer),version);}
    RequirementInput requirement(String kind,String status,Long documentId){return new RequirementInput("RUC vigente",kind,status,manager,deadline,documentId,"",0);}
    Map<String,Object> actor(long uid){return db.one("SELECT * FROM app_user WHERE id=?",uid);}
    MockMultipartFile txt(){return new MockMultipartFile("file","bases.txt","text/plain","Documento: Presentar RUC vigente.\nRequisito: Incluir cronograma.\nFecha límite: 2026-10-01.".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Test void authenticationAndCsrfAreRequired()throws Exception{
        mvc.perform(get("/api/tenders")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tenders").with(user("admin@example.com")).contentType("application/json").content(json.writeValueAsString(tender("RECIBIDA",0)))).andExpect(status().isForbidden());
        mvc.perform(formLogin().user("admin@example.com").password("TestPassword2026!")).andExpect(status().is3xxRedirection());
        mvc.perform(formLogin().user("admin@example.com").password("bad")).andExpect(redirectedUrl("/login?error"));
        assertThat(admin.get("password_hash").toString()).startsWith("$2a$");
        assertThat(encoder.matches("TestPassword2026!",admin.get("password_hash").toString())).isTrue();
    }
    @Test void tenderPaginationFiltersAndReturnsStableMetadata()throws Exception{
        mvc.perform(get("/api/tenders?page=0&size=1&q=TEST-001&status=EN_PREPARACION").with(user("admin@example.com")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1));
    }
    @Test void veryLargeTenderPageIsSafeAndLegacyResponseStaysAnArray()throws Exception{
        mvc.perform(get("/api/tenders?page=2147483647&size=100").with(user("admin@example.com")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/tenders").with(user("admin@example.com")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("TEST-001"));
    }
    @Test void auditPaginationClampsSizeAndReturnsTotal()throws Exception{
        mvc.perform(get("/api/audit?page=0&size=1000").with(user("admin@example.com")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100))
            .andExpect(jsonPath("$.totalItems").isNumber()).andExpect(jsonPath("$.items").isArray());
    }
    @Test void assignedViewerCanReadButNotWrite()throws Exception{
        mvc.perform(get("/api/tenders/"+tid).with(user("consulta@example.com"))).andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false));
        mvc.perform(post("/api/tenders/"+tid+"/requirements").with(user("consulta@example.com")).with(csrf()).contentType("application/json").content(json.writeValueAsString(requirement("DOCUMENTO","PENDIENTE",null)))).andExpect(status().isForbidden());
    }
    @Test void unassignedUserCannotReadProcessDocumentsOrChat()throws Exception{
        long did=documents.upload(tid,"BASES",txt(),admin);
        mvc.perform(get("/api/tenders/"+tid).with(user("otro@example.com"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/tenders/"+tid+"/documents/"+did+"/download").with(user("otro@example.com"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/tenders/"+tid+"/chat").with(user("otro@example.com")).with(csrf()).contentType("application/json").content("{\"question\":\"RUC\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/dashboard").with(user("otro@example.com"))).andExpect(jsonPath("$.tenders.length()").value(0));
    }
    @Test void managerCannotGrantAccessOrCreateProcesses()throws Exception{
        var changed=new TenderInput("TEST-001","Proceso privado","Entidad Demo","Descripción",deadline.minusDays(5).toLocalDate(),deadline,"EN_REVISION","ALTA",manager,"",List.of(manager,viewer,other),0);
        assertThatThrownBy(()->flow.saveTender(tid,changed,actor(manager))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        mvc.perform(post("/api/tenders").with(user("gestor@example.com")).with(csrf()).contentType("application/json").content(json.writeValueAsString(tender("RECIBIDA",0)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").with(user("gestor@example.com"))).andExpect(status().isForbidden());
    }
    @Test void optimisticLockPreventsLostUpdates(){
        flow.saveTender(tid,tender("EN_REVISION",0),actor(manager));
        assertThatThrownBy(()->flow.saveTender(tid,tender("RECIBIDA",0),actor(manager))).hasMessageContaining("409");
        assertThat(db.one("SELECT status FROM tender WHERE id=?",tid).get("status")).isEqualTo("EN_REVISION");
    }
    @Test void invalidAssigneeAndCrossProcessDocumentAreRejected()throws Exception{
        var in=new RequirementInput("Revisar", "TECNICO","PENDIENTE",other,deadline,null,"",0);
        assertThatThrownBy(()->flow.saveRequirement(tid,null,in,admin)).hasMessageContaining("asignado");
        long foreignTender=db.insert("tender",fields("code","OTHER","title","Otro","entity","Demo","description","Texto","published_on",deadline.toLocalDate(),"deadline",deadline,"status","RECIBIDA","priority","MEDIA","owner_id",other));
        long did=documents.upload(foreignTender,"BASES",txt(),admin);
        assertThatThrownBy(()->flow.saveRequirement(tid,null,requirement("DOCUMENTO","COMPLETADO",did),admin)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void documentaryRequirementNeedsEvidenceAndReadinessChecksChecklist(){
        assertThatThrownBy(()->flow.saveRequirement(tid,null,requirement("DOCUMENTO","COMPLETADO",null),admin)).hasMessageContaining("Vincula");
        flow.saveRequirement(tid,null,requirement("DOCUMENTO","PENDIENTE",null),admin);
        assertThatThrownBy(()->flow.saveTender(tid,tender("LISTA_PARA_PRESENTAR",0),admin)).hasMessageContaining("Completa");
    }
    @Test void taskConversionIsIdempotentAndDoesNotCompleteRequirement(){
        long rid=flow.saveRequirement(tid,null,requirement("DOCUMENTO","PENDIENTE",null),admin);
        long task=flow.convertTask(tid,rid,admin);assertThat(flow.convertTask(tid,rid,admin)).isEqualTo(task);
        flow.saveTask(tid,task,new TaskInput("Preparar RUC","",manager,deadline,"ALTA","COMPLETADA","",0),admin);
        assertThat(db.one("SELECT status FROM requirement WHERE id=?",rid).get("status")).isEqualTo("PENDIENTE");
    }
    @Test void uploadAnalysisRequiresHumanReviewAndKeepsEvidenceSeparate()throws Exception{
        long did=documents.upload(tid,"BASES",txt(),admin);long run=analysis.analyze(tid,did,admin);
        assertThat(analysis.analyze(tid,did,admin)).isEqualTo(run);
        assertThat(db.count("SELECT count(*) FROM requirement WHERE tender_id=?",tid)).isZero();
        var suggested=db.one("SELECT * FROM suggestion WHERE analysis_id=? AND kind='DOCUMENTO'",run);long sid=id(suggested.get("id"));
        analysis.review(tid,sid,new Review("ACEPTAR","RUC revisado por usuario","DOCUMENTO",manager,deadline),admin);
        var req=db.one("SELECT * FROM requirement WHERE tender_id=?",tid);
        assertThat(req.get("description")).isEqualTo("RUC revisado por usuario");assertThat(req.get("document_id")).isNull();assertThat(req.get("status")).isEqualTo("PENDIENTE");
        assertThatThrownBy(()->analysis.review(tid,sid,new Review("ACEPTAR","Duplicado","DOCUMENTO",manager,deadline),admin)).hasMessageContaining("409");
    }
    @Test void reviewedDateCreatesMilestoneWithoutChangingOfficialDeadline()throws Exception{
        long did=documents.upload(tid,"BASES",txt(),admin);long run=analysis.analyze(tid,did,admin);long sid=id(db.one("SELECT id FROM suggestion WHERE analysis_id=? AND kind='FECHA'",run).get("id"));
        analysis.review(tid,sid,new Review("ACEPTAR","Consulta interna","FECHA",manager,deadline.minusDays(1)),admin);
        assertThat(db.count("SELECT count(*) FROM milestone WHERE tender_id=?",tid)).isEqualTo(1);
        assertThat(pe.licitagestion.domain.Rules.instant(db.one("SELECT deadline FROM tender WHERE id=?",tid).get("deadline"))).isEqualTo(deadline.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    @Test void pdfTextIsExtractedWithPageCitations()throws Exception{
        byte[] data;try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
            var page=new PDPage();pdf.addPage(page);try(var content=new PDPageContentStream(pdf,page)){content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(50,700);content.showText("Documento: Certificado tecnico obligatorio.");content.endText();}pdf.save(out);data=out.toByteArray();
        }
        long did=documents.upload(tid,"BASES",new MockMultipartFile("file","bases.pdf","application/pdf",data),admin);
        var sources=documents.sources(documents.get(tid,did,admin));assertThat(sources).hasSize(1);assertThat(sources.getFirst().id()).endsWith(":p:1");
        assertThat(sources.getFirst().text()).contains("Certificado tecnico");
    }
    @Test void fakePdfAndMalformedTxtAreRejected(){
        assertThatThrownBy(()->documents.upload(tid,"BASES",new MockMultipartFile("file","bad.pdf","application/pdf","fake".getBytes()),admin)).hasMessageContaining("PDF");
        assertThatThrownBy(()->documents.upload(tid,"BASES",new MockMultipartFile("file","bad.txt","text/plain",new byte[]{(byte)0xff}),admin)).hasMessageContaining("UTF-8");
        assertThatThrownBy(()->documents.upload(tid,"BASES",new MockMultipartFile("file","bad.html","text/html","<script>".getBytes()),admin)).hasMessageContaining("PDF y TXT");
    }
    @Test void chatPersistsCitationsAndCannotUseForeignSources()throws Exception{
        long did=documents.upload(tid,"BASES",txt(),admin);
        var answer=analysis.ask(tid,"¿Qué documento RUC solicitan?",actor(viewer));
        assertThat(answer.citations()).anyMatch(c->c.sourceId().startsWith("doc:"+did));
        assertThat(db.count("SELECT count(*) FROM chat_message WHERE tender_id=?",tid)).isEqualTo(1);
        assertThat(analysis.ask(tid,"¿Cuál es el clima en Saturno?",actor(viewer)).answer()).contains("No encuentro");
    }
    @Test void dashboardTracksMissingDocumentsAndOverdueTasks(){
        flow.saveRequirement(tid,null,requirement("DOCUMENTO","PENDIENTE",null),admin);
        flow.saveTask(tid,null,new TaskInput("Atrasada","",manager,OffsetDateTime.now().minusHours(1),"ALTA","PENDIENTE","",0),admin);
        var metrics=(Map<?,?>)alerts.dashboard(actor(manager)).get("metrics");
        assertThat(metrics.get("missingDocuments")).isEqualTo(1);assertThat(metrics.get("pendingTasks")).isEqualTo(1);
        assertThat(flow.tasks(tid).getFirst().get("timing")).isEqualTo("ATRASADA");
    }
    @Test void disabledUserImmediatelyLosesApiAccess()throws Exception{
        db.update("UPDATE app_user SET enabled=FALSE WHERE id=?",manager);
        mvc.perform(get("/api/dashboard").with(user("gestor@example.com"))).andExpect(status().isForbidden());
    }
    @Test void cannotDisableLastAdmin(){
        assertThatThrownBy(()->flow.saveUser(id(admin.get("id")),new UserInput("Admin","admin@example.com","","CONSULTA",false,"",false),admin)).hasMessageContaining("administrador activo");
    }
    @Test void dryRunWhatsappIsExplicitAndIdempotent(){
        assertThatThrownBy(()->notifications.prepare("tender:"+tid,manager,admin)).hasMessageContaining("consentimiento");
        db.update("UPDATE app_user SET phone='+51999999999',whatsapp_opt_in=TRUE WHERE id=?",manager);
        long nid=notifications.prepare("tender:"+tid,manager,admin);assertThat(notifications.prepare("tender:"+tid,manager,admin)).isEqualTo(nid);
        notifications.send(nid,admin);assertThat(db.one("SELECT status FROM notification WHERE id=?",nid).get("status")).isEqualTo("SIMULADA");
        assertThatThrownBy(()->notifications.send(nid,admin)).hasMessageContaining("procesado");
    }
    @Test void staleWhatsappDraftIsNotSent(){
        db.update("UPDATE app_user SET phone='+51999999999',whatsapp_opt_in=TRUE WHERE id=?",manager);
        long nid=notifications.prepare("tender:"+tid,manager,admin);
        db.update("UPDATE tender SET deadline=? WHERE id=?",deadline.plusHours(2),tid);
        assertThatThrownBy(()->notifications.send(nid,admin)).hasMessageContaining("cambió");
        assertThat(db.one("SELECT status FROM notification WHERE id=?",nid).get("status")).isEqualTo("BORRADOR");
    }
    @Test void validApiMutationReturnsPersistedState()throws Exception{
        mvc.perform(post("/api/tenders/"+tid+"/requirements").with(user("gestor@example.com")).with(csrf()).contentType("application/json").content(json.writeValueAsString(requirement("LEGAL","PENDIENTE",null)))).andExpect(status().isOk()).andExpect(jsonPath("$.id").isNumber());
        mvc.perform(get("/api/tenders/"+tid).with(user("gestor@example.com"))).andExpect(jsonPath("$.requirements.length()").value(1));
    }
    @Test void inventedProviderCitationsAreRejectedWithoutPersistingAnalysis()throws Exception{
        long did=documents.upload(tid,"BASES",txt(),admin);
        var fabricated=new pe.licitagestion.ai.AssistantProvider.Extraction("Resumen",List.of(new pe.licitagestion.ai.AssistantProvider.Finding("DOCUMENTO","Certificado inventado","doc:999:p:1","Texto inexistente")));
        org.mockito.Mockito.doReturn(fabricated).when(localProvider).extract(org.mockito.ArgumentMatchers.anyList());
        mvc.perform(post("/api/tenders/"+tid+"/documents/"+did+"/analyze").with(user("gestor@example.com")).with(csrf())).andExpect(status().isUnprocessableEntity());
        assertThat(db.count("SELECT count(*) FROM analysis_run WHERE document_id=?",did)).isZero();
    }
    @Test void missingDocumentsAnswerExcludesCompletedNonDocumentaryRequirements(){
        flow.saveRequirement(tid,null,requirement("DOCUMENTO","PENDIENTE",null),admin);
        flow.saveRequirement(tid,null,new RequirementInput("Revisión técnica terminada","TECNICO","COMPLETADO",manager,deadline,null,"",0),admin);
        var answer=analysis.ask(tid,"¿Qué documentos faltan y quién es responsable?",admin);
        assertThat(answer.answer()).contains("RUC vigente").doesNotContain("Revisión técnica terminada");
    }
    @Test void allTasksEndpointReturnsVisibleTasks()throws Exception{
        flow.saveTask(tid,null,new TaskInput("Tarea general","Detalle",manager,deadline,"ALTA","PENDIENTE","",0),admin);
        mvc.perform(get("/api/tasks").with(user("gestor@example.com"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Tarea general")).andExpect(jsonPath("$[0].code").value("TEST-001"));
        mvc.perform(get("/api/tasks").with(user("otro@example.com"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void loginRateLimiterBlocksAfterConsecutiveFailures()throws Exception{
        var limiter=new pe.licitagestion.security.LoginRateLimiter();
        for(int i=0;i<5;i++) limiter.recordFailure("192.168.1.100");
        assertThat(limiter.isBlocked("192.168.1.100")).isTrue();
        limiter.recordSuccess("192.168.1.100");
        assertThat(limiter.isBlocked("192.168.1.100")).isFalse();
    }
    @Test void paginatedTendersEndpointAppliesLimitAndOffset()throws Exception{
        mvc.perform(get("/api/tenders?page=0&size=1").with(user("admin@example.com"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }
    @Test void changingPasswordInvalidatesActiveSession()throws Exception{
        var mvcSession = mvc.perform(get("/api/dashboard").with(user("gestor@example.com")))
            .andExpect(status().isOk())
            .andReturn().getRequest().getSession();
        assertThat(mvcSession).isNotNull();
        Thread.sleep(25);
        flow.saveUser(manager, new UserInput("Ana","gestor@example.com","NewPassword1234!","GESTOR",true,"",false), admin);
        mvc.perform(get("/api/dashboard").with(user("gestor@example.com")).session((org.springframework.mock.web.MockHttpSession)mvcSession))
            .andExpect(status().isForbidden());
    }
}

