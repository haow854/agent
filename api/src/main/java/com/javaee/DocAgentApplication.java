package com.javaee;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.javaee")
@MapperScan({
        "com.javaee.user.mapper",
        "com.javaee.fileservice.mapper",
        "com.javaee.documentservice.mapper",
        "com.javaee.agentservice.memory",
        "com.javaee.agentservice.session"
})
@EnableScheduling
@EnableAsync
public class DocAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocAgentApplication.class, args);
    }
}
