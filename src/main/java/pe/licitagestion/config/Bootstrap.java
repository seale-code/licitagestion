package pe.licitagestion.config;

import java.time.*;
import java.util.*;
import java.io.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import pe.licitagestion.persistence.Store;
import pe.licitagestion.service.*;
import pe.licitagestion.web.Inputs.*;
import static pe.licitagestion.persistence.Store.fields;

@Component
public class Bootstrap implements ApplicationRunner {
    private final Store db;private final Workflow workflow;private final Documents documents;private final Analysis analysis;private final PasswordEncoder encoder;private final Clock clock;
    private final String email,password;private final boolean demo;
    public Bootstrap(Store db,Workflow workflow,Documents documents,Analysis analysis,PasswordEncoder encoder,Clock clock,@Value("${app.bootstrap.email}")String email,@Value("${app.bootstrap.password}")String password,@Value("${app.demo-seed}")boolean demo){this.db=db;this.workflow=workflow;this.documents=documents;this.analysis=analysis;this.encoder=encoder;this.clock=clock;this.email=email;this.password=password;this.demo=demo;}
    @Override @Transactional public void run(ApplicationArguments args)throws Exception{
        if(db.count("SELECT count(*) FROM app_user")>0)return;
        if(email.isBlank()||password.length()<12||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72||password.startsWith("change-"))throw new IllegalStateException("Configura BOOTSTRAP_EMAIL y una contraseña inicial segura de 12 a 72 bytes");
        long adminId=db.insert("app_user",fields("name","Administrador","email",email.toLowerCase(Locale.ROOT),"password_hash",encoder.encode(password),"role","ADMIN"));
        var actor=db.one("SELECT * FROM app_user WHERE id=?",adminId);
        workflow.audit(actor,null,"SISTEMA_INICIALIZADO","Administrador inicial creado");
        if(!demo)return;
        long manager=workflow.saveUser(null,new UserInput("Ana Torres","gestor@example.com","DemoUniversitaria2026!","GESTOR",true,"",false),actor);
        long viewer=workflow.saveUser(null,new UserInput("Carlos Ruiz","consulta@example.com","DemoUniversitaria2026!","CONSULTA",true,"",false),actor);
        long other=workflow.saveUser(null,new UserInput("Lucía Vega","otro@example.com","DemoUniversitaria2026!","GESTOR",true,"",false),actor);
        var now=OffsetDateTime.now(clock).withSecond(0).withNano(0);
        long first=workflow.saveTender(null,new TenderInput("LP-2026-014","Servicio de gestión documental","Municipalidad de Ejemplo","Preparar la propuesta técnica y económica para el servicio de gestión documental. Datos ficticios para demostrar el flujo interno.",now.minusDays(8).toLocalDate(),now.plusDays(2),"EN_PREPARACION","ALTA",manager,"Revisar anexos antes de presentar.",List.of(manager,viewer),0),actor);
        long second=workflow.saveTender(null,new TenderInput("AS-2026-032","Mantenimiento de equipos informáticos","Universidad Ficticia","Organizar certificados y propuesta económica.",now.minusDays(10).toLocalDate(),now.minusHours(6),"PENDIENTE_DOCUMENTOS","ALTA",manager,"Pendiente de confirmación del área técnica.",List.of(manager,viewer),0),actor);
        workflow.saveTender(null,new TenderInput("CP-2026-008","Consultoría de procesos internos","Instituto Demo","Proceso privado de otro equipo, oculto al gestor Ana y a consulta.",now.minusDays(2).toLocalDate(),now.plusDays(10),"EN_REVISION","MEDIA",other,"",List.of(other),0),actor);
        workflow.saveTender(null,new TenderInput("LP-2026-005","Implementación de portal institucional","Entidad Ficticia","Propuesta ya presentada, pendiente de resultado.",now.minusDays(20).toLocalDate(),now.minusDays(2),"PRESENTADA","MEDIA",manager,"Presentación registrada para la demo.",List.of(manager,viewer),0),actor);
        long doc=documents.upload(first,"BASES",demoPdf(now.plusDays(2)),actor);
        analysis.analyze(first,doc,actor);
        workflow.saveRequirement(first,null,new RequirementInput("Presentar RUC vigente","DOCUMENTO","PENDIENTE",manager,now.plusDays(1),null,"",0),actor);
        workflow.saveRequirement(first,null,new RequirementInput("Vigencia de poder del representante","DOCUMENTO","PENDIENTE",manager,now.plusHours(4),null,"Solicitar al área administrativa.",0),actor);
        workflow.saveRequirement(first,null,new RequirementInput("Revisar alcance técnico de las bases","TECNICO","COMPLETADO",manager,now.minusHours(2),null,"Revisión completada en la demo.",0),actor);
        workflow.saveTask(first,null,new TaskInput("Preparar propuesta técnica","Definir arquitectura y cronograma.",manager,now.plusHours(20),"ALTA","EN_PROGRESO","",0),actor);
        workflow.saveTask(second,null,new TaskInput("Recopilar certificados","Confirmar certificados del equipo.",manager,now.minusHours(10),"ALTA","PENDIENTE","",0),actor);
        workflow.saveMilestone(first,null,new MilestoneInput("Cierre de consultas","CONSULTAS",now.plusHours(3),manager,false,"Revisar dudas sobre el alcance.",0),actor);
        workflow.saveMilestone(first,null,new MilestoneInput("Revisión interna de propuesta","INTERNA",now.plusDays(1),adminId,false,"Validación final del equipo.",0),actor);
    }
    private MultipartFile demoPdf(OffsetDateTime deadline)throws IOException{
        byte[] bytes;
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
            var page=new PDPage();pdf.addPage(page);
            try(var stream=new PDPageContentStream(pdf,page)){
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),11);stream.newLineAtOffset(45,735);stream.setLeading(22);
                for(String line:List.of("BASES DE DEMOSTRACION - LP-2026-014","Entidad ficticia. Documento para uso academico.","Fecha limite de presentacion: "+deadline.toLocalDate()+" a las "+deadline.toLocalTime()+" UTC.","Documento: Presentar RUC vigente.","Documento: Vigencia de poder del representante legal.","Requisito: Incluir cronograma detallado del servicio.","Experiencia requerida: acreditar dos proyectos similares.","Presupuesto referencial: S/ 120000.","Criterio de evaluacion: propuesta tecnica y experiencia.","Penalidad: revisar las condiciones del contrato de ejemplo.","Condicion: La propuesta debe estar firmada.")){stream.showText(line);stream.newLine();}
                stream.endText();
            }
            pdf.save(out);bytes=out.toByteArray();
        }
        final byte[] content=bytes;
        return new MultipartFile(){public String getName(){return "file";}public String getOriginalFilename(){return "bases-demo.pdf";}public String getContentType(){return "application/pdf";}public boolean isEmpty(){return false;}public long getSize(){return content.length;}public byte[] getBytes(){return content;}public InputStream getInputStream(){return new ByteArrayInputStream(content);}public void transferTo(File dest)throws IOException{java.nio.file.Files.write(dest.toPath(),content);}};
    }
}

