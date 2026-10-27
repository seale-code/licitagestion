package pe.licitagestion.ai;

import java.util.List;

public interface AssistantProvider {
    record Source(String id,String label,String text){}
    record Finding(String kind,String description,String sourceId,String quote){}
    record Extraction(String summary,List<Finding> findings){}
    record Citation(String sourceId,String label,String quote){}
    record Answer(String answer,List<Citation> citations){}
    String name();
    Extraction extract(List<Source> sources);
    Answer answer(String question,List<Source> sources);
}

