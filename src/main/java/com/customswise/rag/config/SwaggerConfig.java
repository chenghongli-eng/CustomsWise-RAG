package com.customswise.rag.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CustomsWise RAG API")
                        .description("跨境电商海关政策智能RAG助手 API文档")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("CustomsWise")
                                .email("support@customswise.com")
                                .url("https://github.com/chenghongli-eng/CustomsWise-RAG"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("本地开发服务器")
                ));
    }
}
