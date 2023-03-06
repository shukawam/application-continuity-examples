package com.oracle.jp;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/ucp/ac")
public class ACUCPStarter {

    private final ACUCPService ac_UCPService;

    /**
     * @param ac_UCPService
     */
    @Inject
    public ACUCPStarter(ACUCPService ac_UCPService) {
        this.ac_UCPService = ac_UCPService;
    }

    @POST
    @Path("/start")
    @Produces(MediaType.TEXT_PLAIN)
    public String start() {
        ac_UCPService.exec();
        return "ok";
    }

    @GET
    @Path("/count")
    public String count() {
        return String.format("Include %s rows.", ac_UCPService.count());
    }

    @DELETE
    @Path("/delete")
    public String deleteAllRows() {
        return String.format("Delete %s rows.", ac_UCPService.count());
    }
}
