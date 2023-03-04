package com.oracle.jp;

import java.sql.Date;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "ACData")
@Table(name = "AC_TEST_TABLE")
@Access(AccessType.FIELD)
public class ACData {
    @Id
    @Column(name = "C1")
    private int c1;

    @Column(name = "C2")
    private String c2;

    @Column(name = "C3")
    private Date c3;

    /**
     * @param c1
     * @param c2
     * @param c3
     */
    public ACData(int c1, String c2, Date c3) {
        this.c1 = c1;
        this.c2 = c2;
        this.c3 = c3;
    }

    /**
     * @return the c1
     */
    public int getC1() {
        return c1;
    }

    /**
     * @param c1 the c1 to set
     */
    public void setC1(int c1) {
        this.c1 = c1;
    }

    /**
     * @return the c2
     */
    public String getC2() {
        return c2;
    }

    /**
     * @param c2 the c2 to set
     */
    public void setC2(String c2) {
        this.c2 = c2;
    }

    /**
     * @return the c3
     */
    public Date getC3() {
        return c3;
    }

    /**
     * @param c3 the c3 to set
     */
    public void setC3(Date c3) {
        this.c3 = c3;
    }

}
