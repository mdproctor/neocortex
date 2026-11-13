package io.casehub.neocortex.cognitive.observability;

public class DerivationTrace implements DerivationObserver {

    public sealed interface Entry permits PipelineStarted, StageRecorded, FlowRecorded, PipelineCompleted {}

    public record PipelineStarted(String agentId, String eventRef, PipelineInput input) implements Entry {}

    public record StageRecorded(String agentId, String eventRef, StageComputation computation) implements Entry {}

    public record FlowRecorded(String agentId, String eventRef, DataFlow flow) implements Entry {}

    public record PipelineCompleted(String agentId, String eventRef) implements Entry {}

    private final java.util.List<Entry>                entries = new java.util.ArrayList<>();
    private final java.util.function.Predicate<String> stageFilter;

    public DerivationTrace() {this(s -> true);}

    public DerivationTrace(java.util.function.Predicate<String> stageFilter) {
        this.stageFilter = stageFilter;
    }

    @Override
    public void onPipelineStart(String agentId, String eventRef, PipelineInput input) {
        entries.add(new PipelineStarted(agentId, eventRef, input));
    }

    @Override
    public void onStageComputation(String agentId, String eventRef, StageComputation stage) {
        if (stageFilter.test(stage.method())) {
            entries.add(new StageRecorded(agentId, eventRef, stage));
        }
    }

    @Override
    public void onDataFlow(String agentId, String eventRef, DataFlow flow) {
        entries.add(new FlowRecorded(agentId, eventRef, flow));
    }

    @Override
    public void onPipelineComplete(String agentId, String eventRef) {
        entries.add(new PipelineCompleted(agentId, eventRef));
    }

    public java.util.List<Entry> entries() {return java.util.Collections.unmodifiableList(entries);}

    public java.util.List<StageComputation> stages() {
        return entries.stream()
                      .filter(e -> e instanceof StageRecorded)
                      .map(e -> ((StageRecorded) e).computation())
                      .toList();
    }

    public java.util.List<StageComputation> stagesFor(String method) {
        return stages().stream().filter(s -> method.equals(s.method())).toList();
    }

    public java.util.List<FacetEffect> allFacetEffects() {
        return stages().stream().flatMap(s -> s.facetEffects().stream()).toList();
    }

    public java.util.List<FacetEffect> effectsOf(String facet) {
        return allFacetEffects().stream().filter(e -> facet.equals(e.facet())).toList();
    }

    public java.util.List<FacetEffect> effectsIn(String method) {
        return stagesFor(method).stream().flatMap(s -> s.facetEffects().stream()).toList();
    }

    public java.util.List<DataFlow> flows() {
        return entries.stream()
                      .filter(e -> e instanceof FlowRecorded)
                      .map(e -> ((FlowRecorded) e).flow())
                      .toList();
    }

    public java.util.List<Entry> entriesForEvent(String eventRef) {
        return entries.stream()
                      .filter(e -> switch (e) {
                          case PipelineStarted p -> eventRef.equals(p.eventRef());
                          case StageRecorded s -> eventRef.equals(s.eventRef());
                          case FlowRecorded f -> eventRef.equals(f.eventRef());
                          case PipelineCompleted p -> eventRef.equals(p.eventRef());
                      })
                      .toList();
    }

    public boolean isEmpty() {return entries.isEmpty();}

    public int size()        {return entries.size();}
}
