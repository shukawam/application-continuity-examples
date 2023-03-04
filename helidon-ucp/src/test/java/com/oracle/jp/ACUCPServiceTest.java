package com.oracle.jp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import io.helidon.microprofile.tests.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@HelidonTest
public class ACUCPServiceTest {
    private static final Logger logger = Logger.getLogger(ACUCPServiceTest.class.getName());
    private final ACUCPService acucpService;

    @PersistenceContext(unitName = "pu1")
    private EntityManager entityManager;

    /**
     * @param acucpService
     */
    @Inject
    public ACUCPServiceTest(ACUCPService acucpService) {
        this.acucpService = acucpService;
    }

    

    // @AfterAll
    // void afterClass() {
    //     entityManager.createNativeQuery("delete from AC_TEST_TABLE");
    // }



    /**
     * Insert 100 rows.
     */
    @Test
    void shouldInsert100Rows() {
        acucpService.exec();
        var result = entityManager.createNativeQuery("select count(*) from AC_TEST_TABLE").getSingleResult();
        logger.info(result.toString());
        assertEquals(BigDecimal.valueOf(100), result);
    }

}
