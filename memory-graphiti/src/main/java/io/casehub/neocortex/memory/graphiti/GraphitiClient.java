package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.graphiti.dto.AddMessagesRequest;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiEpisodicNode;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiSearchRequest;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiSearchResponse;
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
public interface GraphitiClient {

    @POST @Path("/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    Response addMessages(AddMessagesRequest request);

    @POST @Path("/search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    GraphitiSearchResponse search(GraphitiSearchRequest request);

    @GET @Path("/episodes/{groupId}")
    @Produces(MediaType.APPLICATION_JSON)
    List<GraphitiEpisodicNode> getEpisodes(
        @PathParam("groupId") String groupId,
        @QueryParam("last_n") int lastN);

    @DELETE @Path("/group/{groupId}")
    void deleteGroup(@PathParam("groupId") String groupId);

    @DELETE @Path("/episode/{uuid}")
    void deleteEpisode(@PathParam("uuid") String uuid);
}
