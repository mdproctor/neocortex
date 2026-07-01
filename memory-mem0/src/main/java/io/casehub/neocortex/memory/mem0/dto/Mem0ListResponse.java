package io.casehub.neocortex.memory.mem0.dto;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record Mem0ListResponse(
    @JsonProperty("results") List<Mem0Memory> results
) {}
