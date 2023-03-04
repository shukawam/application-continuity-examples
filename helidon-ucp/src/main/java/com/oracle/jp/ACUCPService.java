package com.oracle.jp;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

/**
 * The sample demonstrates UCP as client side connection pool.
 */
@ApplicationScoped
public class ACUCPService {
    private static final Logger logger = Logger.getLogger(ACUCPService.class.getName());

    @PersistenceContext(unitName = "pu1")
    private EntityManager entityManager;

    @Transactional
    public void exec() {
        IntStream.rangeClosed(1, 100).forEach(i -> {
            ACData acData = new ACData(i, "Data" + i, new Date(System.currentTimeMillis()));
            logger.info("insert" + " " + i + " " + "row...");
            entityManager.persist(acData);
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException("Unexpected interrupt", e);
            }
        });
    }

    public BigDecimal count() {
        return (BigDecimal) entityManager.createNativeQuery("select count(*) from AC_TEST_TABLE").getSingleResult();
    }

    @Transactional
    public int deleteAllRows() {
        return entityManager.createNativeQuery("delete from AC_TEST_TABLE").executeUpdate();
    }
}
