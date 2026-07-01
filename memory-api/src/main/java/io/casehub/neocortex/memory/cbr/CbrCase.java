package io.casehub.neocortex.memory.cbr;

public interface CbrCase {
    String cbrType();
    String problem();
    String solution();
    String outcome();
    Double confidence();
}
