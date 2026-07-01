package io.casehub.neocortex.memory.graphiti.dto;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GraphitiSearchResponse(
        @JsonProperty("facts") List<FactResult> facts
) {}
