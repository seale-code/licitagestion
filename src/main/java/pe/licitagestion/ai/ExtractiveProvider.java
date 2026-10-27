package pe.licitagestion.ai;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

@Component
public class ExtractiveProvider implements AssistantProvider {
    public String name(){return "extractivo-local-v1 (sin LLM)";}
    public static String normal(String text){return Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","");}
    public Extraction extract(List<Source> sources){
        var findings=new ArrayList<Finding>();var seen=new HashSet<String>();
        for(var source:sources)for(String line:source.text().split("\\R")){
            String clean=line.strip();if(clean.length()<8||clean.length()>1500)continue;
            String n=normal(clean),kind=null;
            if(n.matches(".*(fecha|plazo|cierre|presentacion).*\\d.*"))kind="FECHA";
            else if(n.matches(".*(monto|presupuesto|valor referencial|s/).*\\d.*"))kind="MONTO";
            else if(n.contains("penalidad")||n.contains("penalidades"))kind="PENALIDAD";
            else if(n.contains("criterio")||n.contains("puntaje"))kind="CRITERIO";
            else if(n.contains("experiencia"))kind="EXPERIENCIA";
            else if(n.contains("certificado")||n.contains("declaracion")||n.contains("ruc")||n.contains("vigencia de poder")||n.startsWith("documento:"))kind="DOCUMENTO";
            else if(n.startsWith("requisito:")||n.startsWith("se requiere")||n.contains("obligatorio"))kind="REQUISITO";
            else if(n.startsWith("condicion"))kind="CONDICION";
            if(kind!=null&&seen.add(n)&&findings.size()<40)findings.add(new Finding(kind,clean,source.id(),clean));
        }
        String summary=sources.stream().map(Source::text).filter(t->!t.isBlank()).findFirst().orElse("");
        summary=summary.isBlank()?"No se encontró texto extraíble. Si el PDF es escaneado, requiere OCR externo o una versión con texto.":"Extracto local; no es un resumen generativo: "+summary.substring(0,Math.min(1100,summary.length()));
        return new Extraction(summary,findings);
    }
    public Answer answer(String question,List<Source> sources){
        String q=normal(question);
        boolean missingDocuments=q.contains("document")&&(q.contains("falt")||q.contains("pendient"));
        boolean pendingTasks=q.contains("tarea")&&(q.contains("pendient")||q.contains("falt"));
        Set<String> stop=Set.of("que","cual","cuales","quien","como","para","los","las","una","del","nos","esta","este","son","hay","con","por","cada","de","el","la","es","se","en","un","y","lo");
        Set<String> words=new HashSet<>(Arrays.asList(q.split("\\W+")));words.removeIf(w->w.length()<3||stop.contains(w));
        if(q.contains("falt")||q.contains("pendient"))words.add("pendiente");
        if(q.contains("fecha")||q.contains("limite")||q.contains("vence"))words.add("cierre");
        if(q.contains("responsable"))words.add("responsable");
        if(q.contains("resume")||q.contains("resumen"))words.add("licitacion");
        record Hit(Source source,String text,int score){}
        var hits=new ArrayList<Hit>();
        for(var source:sources)for(String line:source.text().split("\\R")){
            String n=normal(line);int score=0;
            boolean documentHit=missingDocuments && (source.id().equals("registro:resumen-documentos") || (source.id().startsWith("registro:requisito:")&&n.contains("tipo: documento")&&n.contains("faltante")&&!n.contains("estado: completado")&&!n.contains("estado: no_aplica")));
            boolean taskHit=pendingTasks && (source.id().equals("registro:resumen-tareas") || (source.id().startsWith("registro:tarea:")&&!n.contains("estado: completada")));
            if((missingDocuments||pendingTasks)&&!documentHit&&!taskHit)continue;
            for(String word:words)if(n.contains(word))score++;
            if((q.contains("tarea")||q.contains("responsable")||q.contains("falt"))&&source.id().startsWith("registro:"))score+=score>0?2:0;
            if(score>0&&!line.isBlank())hits.add(new Hit(source,line.strip(),score));
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed());
        var citations=new ArrayList<Citation>();var seen=new HashSet<String>();var answer=new StringBuilder("Encontré estos fragmentos en esta licitación (consulta extractiva, sin LLM):\n");
        for(var hit:hits){
            if(citations.size()>=6)break;
            String quote=hit.text().substring(0,Math.min(1500,hit.text().length()));
            if(seen.add(quote)){citations.add(new Citation(hit.source().id(),hit.source().label(),quote));answer.append("• ").append(quote).append("\n");}
        }
        if(citations.isEmpty())return new Answer("No encuentro esa información en los documentos y registros disponibles de esta licitación. No puedo confirmarla.",List.of());
        answer.append("Estos fragmentos pueden no resolver toda la pregunta. Verifica su alcance en la fuente; no infiero datos ausentes.");
        return new Answer(answer.toString(),citations);
    }
}

