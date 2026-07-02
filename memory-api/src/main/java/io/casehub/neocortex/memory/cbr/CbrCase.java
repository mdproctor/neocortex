package io.casehub.neocortex.memory.cbr;

import java.util.Map;

public interface CbrCase {
    String cbrType();
    String problem();
    String solution();
    String outcome();
    Double confidence();
    default Map<String, Object> features() { return Map.of(); }
}
