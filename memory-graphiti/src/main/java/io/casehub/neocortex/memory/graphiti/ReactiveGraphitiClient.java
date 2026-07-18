package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.graphiti.dto.AddMessagesRequest;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiEpisodicNode;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiSearchRequest;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiSearchResponse;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "graphiti")
@RegisterProvider(GraphitiAuthFilter.class)
@Path("/")
public interface ReactiveGraphitiClient {

    @POST @Path("/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<Response> addMessages(AddMessagesRequest request);

    @POST @Path("/search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<GraphitiSearchResponse> search(GraphitiSearchRequest request);

    @GET @Path("/episodes/{groupId}")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<List<GraphitiEpisodicNode>> getEpisodes(
        @PathParam("groupId") String groupId,
        @QueryParam("last_n") int lastN);

    @DELETE @Path("/group/{groupId}")
    Uni<Void> deleteGroup(@PathParam("groupId") String groupId);

    @DELETE @Path("/episode/{uuid}")
    Uni<Void> deleteEpisode(@PathParam("uuid") String uuid);
}
