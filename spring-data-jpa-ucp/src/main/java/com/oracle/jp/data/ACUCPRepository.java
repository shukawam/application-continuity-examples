package com.oracle.jp.data;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ACUCPRepository extends CrudRepository<ACData, Integer> {

}
