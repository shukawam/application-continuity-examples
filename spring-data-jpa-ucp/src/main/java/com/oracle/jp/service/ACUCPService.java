package com.oracle.jp.service;

import java.sql.Date;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.oracle.jp.data.ACData;
import com.oracle.jp.data.ACUCPRepository;

import jakarta.transaction.Transactional;

@Service
public class ACUCPService {
    private static final Logger logger = LoggerFactory.getLogger(ACUCPService.class);
    private final ACUCPRepository acucpRepository;

    /**
     * @param acucpRepository
     */
    public ACUCPService(ACUCPRepository acucpRepository) {
        this.acucpRepository = acucpRepository;
    }

    @Transactional
    public void exec() {
        IntStream.rangeClosed(1, 100).forEach(i -> {
            ACData acData = new ACData(i, "Data" + i, new Date(System.currentTimeMillis()));
            logger.info("insert" + " " + i + " " + "row...");
            acucpRepository.save(acData);
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException("Unexpected interrupt", e);
            }
        });
    }

    public long count() {
        return acucpRepository.count();
    }

    @Transactional
    public void deleteAllRows() {
        acucpRepository.deleteAll();
    }
}
