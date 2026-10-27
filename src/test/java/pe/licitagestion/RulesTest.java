package pe.licitagestion;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import pe.licitagestion.ai.*;
import pe.licitagestion.ai.AssistantProvider.*;
import static org.assertj.core.api.Assertions.*;
import static pe.licitagestion.domain.Rules.*;

class RulesTest {
    final Instant now=Instant.parse("2026-09-28T12:00:00Z");
    @Test void deadlinesArePreciseAndCompletedItemsNeverLate(){
        assertThat(timing(now.minusSeconds(1),now,false)).isEqualTo("ATRASADA");
        assertThat(timing(now,now,false)).isEqualTo("PROXIMA");
        assertThat(timing(now.plus(Duration.ofDays(3)),now,false)).isEqualTo("PROXIMA");
        assertThat(timing(now.plus(Duration.ofDays(3)).plusSeconds(1),now,false)).isEqualTo("EN_PLAZO");
        assertThat(timing(now.minusSeconds(1),now,true)).isEqualTo("COMPLETADA");
    }
    @Test void terminalStatesStopPreparationAlerts(){
        for(String state:List.of("PRESENTADA","ADJUDICADA","NO_ADJUDICADA","CANCELADA"))assertThat(active(state)).isFalse();
        assertThat(active("EN_PREPARACION")).isTrue();
    }
    @Test void extractiveFindingsKeepExactCitations(){
        var source=new Source("doc:1:p:1","bases.pdf · página 1","Documento: RUC vigente.\nRequisito: Cronograma detallado.\nFecha límite: 2026-10-01.\nPresupuesto: S/ 120000.");
        var result=new ExtractiveProvider().extract(List.of(source));
        assertThat(result.findings()).hasSize(4);
        for(var f:result.findings()){assertThat(source.text()).contains(f.quote());assertThat(f.sourceId()).isEqualTo(source.id());}
    }
    @Test void unknownQuestionDoesNotInventAnAnswer(){
        var result=new ExtractiveProvider().answer("¿Cuál es el clima en Saturno?",List.of(new Source("doc:1:p:1","Bases","Documento: RUC vigente.")));
        assertThat(result.citations()).isEmpty();assertThat(result.answer()).contains("No encuentro");
    }
    @Test void localAnswerCitesInternalTasks(){
        var result=new ExtractiveProvider().answer("¿Quién es responsable de las tareas pendientes?",List.of(new Source("registro:tarea:1","Tarea #1","Tarea: preparar propuesta. Estado: PENDIENTE. Responsable: Ana.")));
        assertThat(result.citations()).hasSize(1);assertThat(result.answer()).contains("Ana");
    }
}

