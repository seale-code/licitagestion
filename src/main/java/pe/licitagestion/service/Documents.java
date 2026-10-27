package pe.licitagestion.service;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import pe.licitagestion.persistence.Store;
import pe.licitagestion.security.Access;
import pe.licitagestion.ai.AssistantProvider.Source;
import static pe.licitagestion.persistence.Store.fields;
import static pe.licitagestion.domain.Rules.*;

@Service
public class Documents {
    private final Path root;private final Store db;private final Access access;private final Workflow workflow;private final ObjectMapper json;
    public Documents(@Value("${app.storage}")String path,Store db,Access access,Workflow workflow,ObjectMapper json)throws IOException{
        this.root=Path.of(path).toAbsolutePath().normalize();Files.createDirectories(root);this.db=db;this.access=access;this.workflow=workflow;this.json=json;
    }
    public static String hash(String value){return hash(value.getBytes(StandardCharsets.UTF_8));}
    public static String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new IllegalStateException(e);}}
    @Transactional
    public long upload(long tid,String kind,MultipartFile file,Map<String,Object> actor)throws IOException{
        access.tender(tid,actor,true);workflow.choice(kind,DOCUMENT_TYPES);
        if(file.isEmpty()||file.getSize()>10*1024*1024)throw new IllegalArgumentException("Archivo vacío o mayor a 10 MB");
        String name=Objects.requireNonNullElse(file.getOriginalFilename(),"documento").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");
        if(name.length()>200||name.isBlank())throw new IllegalArgumentException("Nombre de archivo inválido");
        byte[] data=file.getBytes();var pages=new ArrayList<String>();String mime;
        if(name.toLowerCase(Locale.ROOT).endsWith(".pdf")){
            if(data.length<5||!new String(data,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalArgumentException("El contenido no es un PDF válido");
            mime="application/pdf";
            try(var pdf=Loader.loadPDF(data)){
                if(pdf.isEncrypted()||pdf.getNumberOfPages()>100)throw new IllegalArgumentException("El PDF debe estar sin contraseña y tener como máximo 100 páginas");
                var stripper=new PDFTextStripper();int total=0;
                for(int page=1;page<=pdf.getNumberOfPages();page++){
                    stripper.setStartPage(page);stripper.setEndPage(page);String text=stripper.getText(pdf);total+=text.length();
                    if(total>120000)throw new IllegalArgumentException("El texto del documento supera 120 000 caracteres. Divide el archivo.");
                    pages.add(text);
                }
            }catch(IOException e){throw new IllegalArgumentException("No se pudo leer el PDF. Revisa que no esté cifrado o dañado.");}
        }else if(name.toLowerCase(Locale.ROOT).endsWith(".txt")){
            mime="text/plain";
            try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
                if(text.contains("\u0000")||text.length()>120000)throw new IllegalArgumentException("TXT inválido o demasiado largo");pages.add(text);
            }catch(CharacterCodingException e){throw new IllegalArgumentException("El TXT debe usar UTF-8");}
        }else throw new IllegalArgumentException("Solo se admiten PDF y TXT");
        String key=UUID.randomUUID()+".bin";Path target=root.resolve(key);
        Files.write(target,data,StandardOpenOption.CREATE_NEW);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException ignored){}}});
        boolean blank=pages.stream().allMatch(String::isBlank);
        long documentId=db.insert("document",fields("tender_id",tid,"name",name,"kind",kind,"storage_key",key,"mime",mime,"size_bytes",data.length,"sha256",hash(data),"extracted_text",json.writeValueAsString(pages),"extraction_status",blank?"SIN_TEXTO_OCR_PENDIENTE":"TEXTO_EXTRAIDO","uploaded_by",id(actor.get("id"))));
        workflow.audit(actor,tid,"DOCUMENTO_SUBIDO","Documento #"+documentId+"; "+data.length+" bytes");return documentId;
    }
    public Map<String,Object> get(long tid,long did,Map<String,Object> actor){access.tender(tid,actor,false);return db.one("SELECT * FROM document WHERE id=? AND tender_id=?",did,tid);}
    public Path path(Map<String,Object> doc){Path result=root.resolve(doc.get("storage_key").toString()).normalize();if(!result.startsWith(root)||Files.isSymbolicLink(result))throw new IllegalArgumentException("Archivo no disponible");return result;}
    public List<Source> sources(Map<String,Object> doc){
        try{
            var node=json.readTree(doc.get("extracted_text").toString());var sources=new ArrayList<Source>();
            for(int i=0;i<node.size();i++)if(!node.get(i).asText().isBlank())sources.add(new Source("doc:"+doc.get("id")+":p:"+(i+1),doc.get("name")+" · página "+(i+1),node.get(i).asText()));
            return sources;
        }catch(IOException e){throw new IllegalStateException("Texto almacenado inválido");}
    }
    public List<Source> tenderSources(long tid){
        var result=new ArrayList<Source>();for(var doc:db.list("SELECT * FROM document WHERE tender_id=? ORDER BY id",tid))result.addAll(sources(doc));return result;
    }
}

