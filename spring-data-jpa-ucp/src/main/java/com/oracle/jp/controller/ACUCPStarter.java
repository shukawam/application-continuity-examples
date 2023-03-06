package com.oracle.jp.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oracle.jp.service.ACUCPService;

@RestController
@RequestMapping("/ucp/ac")
public class ACUCPStarter {
    private final ACUCPService acucpService;

    /**
     * @param acucpService
     */
    public ACUCPStarter(ACUCPService acucpService) {
        this.acucpService = acucpService;
    }

    @PostMapping(path = "/start")
    public String start() {
        acucpService.exec();
        return "ok";
    }

    @GetMapping(path = "/count")
    public String count() {
        return String.format("Include %s rows.", acucpService.count());
    }

    @DeleteMapping(path = "/delete")
    public String deleteAllRows() {
        var inclueRows = acucpService.count();
        acucpService.deleteAllRows();
        return String.format("Delete %s rows.", inclueRows);
    }

}
