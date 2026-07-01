package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.*;
import io.casehub.neocortex.memory.graphiti.dto.AddMessagesRequest;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiEpisodicNode;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiSearchRequest;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiSearchResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "graphiti")
@RegisterProvider(GraphitiAuthFilter.class)
@Path("/")
public interface GraphitiClient {

    /** Queues an episode for async LLM extraction. Returns 202 Accepted. */
    @POST @Path("/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    Response addMessages(AddMessagesRequest request);

    /** Semantic search across the specified group. */
    @POST @Path("/search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    GraphitiSearchResponse search(GraphitiSearchRequest request);

    /**
     * Fetches the {@code lastN} most recent episodes for a group.
     * Returns the list directly — no wrapper DTO.
     */
    @GET @Path("/episodes/{groupId}")
    @Produces(MediaType.APPLICATION_JSON)
    List<GraphitiEpisodicNode> getEpisodes(
            @PathParam("groupId") String groupId,
            @QueryParam("last_n") int lastN);

    /** Cascading delete of all episodes, entities, and facts for a group. */
    @DELETE @Path("/group/{groupId}")
    void deleteGroup(@PathParam("groupId") String groupId);

    /** Deletes the source episode node. Derived facts/edges are NOT removed. */
    @DELETE @Path("/episode/{uuid}")
    void deleteEpisode(@PathParam("uuid") String uuid);
}
