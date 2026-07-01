package io.casehub.neocortex.memory.graphiti.dto;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record GraphitiEpisodicNode(
        @JsonProperty("uuid")               String uuid,
        @JsonProperty("name")               String name,
        @JsonProperty("group_id")           String groupId,
        @JsonProperty("source")             String source,
        @JsonProperty("source_description") String sourceDescription,
        @JsonProperty("content")            String content,
        @JsonProperty("valid_at")           Instant validAt,
        @JsonProperty("entity_edges")       List<String> entityEdges,
        @JsonProperty("episode_metadata")   Map<String, Object> episodeMetadata,
        @JsonProperty("created_at")         Instant createdAt
) {}
