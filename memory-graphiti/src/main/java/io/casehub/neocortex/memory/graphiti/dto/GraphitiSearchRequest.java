package io.casehub.neocortex.memory.graphiti.dto;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphitiSearchRequest(
        @JsonProperty("group_ids")  List<String> groupIds,
        @JsonProperty("query")      String query,
        @JsonProperty("max_facts")  int maxFacts
) {}
