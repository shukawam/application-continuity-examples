package com.oracle.jp.data;

import java.sql.Date;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity(name = "ACData")
@Table(name = "AC_TEST_TABLE")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ACData {
    @Id
    @Column(name = "C1")
    private Integer c1;
    @Column(name = "c2")
    private String c2;
    @Column(name = "c3")
    private Date c3;
}
