package com.oracle.jp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.oracle.jp.data.ACUCPRepository;

import jakarta.inject.Inject;

@SpringBootTest
public class ACUCPServiceTest {
    private final ACUCPRepository acucpRepository;
    private final ACUCPService acucpService;

    /**
     * @param acucpRepository
     * @param acucpService
     */
    @Inject
    public ACUCPServiceTest(ACUCPRepository acucpRepository, ACUCPService acucpService) {
        this.acucpRepository = acucpRepository;
        this.acucpService = acucpService;
    }

    @Test
    void shouldInsert100Rows() {
        acucpService.exec();
        var actual = acucpRepository.count();
        assertEquals(100, actual);
    }

}
