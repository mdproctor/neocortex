package io.casehub.neocortex.memory.mem0;

import io.casehub.neocortex.memory.mem0.dto.Mem0AddRequest;
import io.casehub.neocortex.memory.mem0.dto.Mem0AddResponse;
import io.casehub.neocortex.memory.mem0.dto.Mem0ListResponse;
import io.casehub.neocortex.memory.mem0.dto.Mem0Memory;
import io.casehub.neocortex.memory.mem0.dto.Mem0SearchRequest;
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
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "mem0")
@RegisterProvider(Mem0AuthFilter.class)
@Path("/")
public interface ReactiveMem0Client {

    @POST
    @Path("/memories")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Mem0AddResponse> add(Mem0AddRequest request);

    @GET
    @Path("/memories")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Mem0ListResponse> list(
        @QueryParam("user_id") String userId,
        @QueryParam("agent_id") String agentId,
        @QueryParam("run_id") String runId);

    @POST
    @Path("/search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Mem0ListResponse> search(Mem0SearchRequest request);

    @GET
    @Path("/memories/{memoryId}")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Mem0Memory> getById(@PathParam("memoryId") String memoryId);

    @DELETE
    @Path("/memories/{memoryId}")
    Uni<Void> deleteById(@PathParam("memoryId") String memoryId);

    @DELETE
    @Path("/memories")
    Uni<Void> deleteAll(
        @QueryParam("user_id") String userId,
        @QueryParam("agent_id") String agentId,
        @QueryParam("run_id") String runId);
}
