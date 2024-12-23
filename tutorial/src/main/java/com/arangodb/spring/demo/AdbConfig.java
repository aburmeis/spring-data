package com.arangodb.spring.demo;

import com.arangodb.ArangoDB;
import com.arangodb.springframework.annotation.EnableArangoRepositories;
import com.arangodb.springframework.config.ArangoConfiguration;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableArangoRepositories(basePackages = {"com.arangodb.spring.demo"})
public class AdbConfig implements ArangoConfiguration {

    @Override
    public ArangoDB.Builder arango() {
        return new ArangoDB.Builder()
                .host("172.28.0.1", 8529)
                .user("root")
                .password("test");
    }

    @Override
    public String database() {
        return "spring-demo";
    }

    @Override
    public boolean returnOriginalEntities() {
        return false;
    }
}
