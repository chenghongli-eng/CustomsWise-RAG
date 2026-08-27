package com.customswise.rag;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CustomsWiseRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomsWiseRagApplication.class, args);
    }

    @Bean
    public CommandLineRunner printStartupInfo(ApplicationContext context) {
        return args -> {
            System.out.println("\n========================================");
            System.out.println("  CustomsWise RAG 启动成功!");
            System.out.println("========================================");
            System.out.println("  API文档 (Knife4j):");
            System.out.println("  → http://localhost:8080/doc.html");
            System.out.println("");
            System.out.println("  OpenAPI JSON:");
            System.out.println("  → http://localhost:8080/v3/api-docs");
            System.out.println("");
            System.out.println("  主页:");
            System.out.println("  → http://localhost:8080/");
            System.out.println("========================================\n");
        };
    }
}
