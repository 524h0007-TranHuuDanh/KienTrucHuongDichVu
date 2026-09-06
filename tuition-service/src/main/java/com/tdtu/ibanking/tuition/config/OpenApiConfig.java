package com.tdtu.ibanking.tuition.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    // Spring Cloud Gateway không giữ lại Host header gốc, nên nếu để springdoc tự
    // suy ra server URL thì Swagger UI sẽ gọi http://tuition-service:8082 (không
    // phân giải được từ trình duyệt). Vì vậy khai báo cứng URL đi qua gateway.
    @Value("${openapi.server-url:http://localhost:8080}")
    private String serverUrl;

    @Bean
    public OpenAPI tuitionServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Tuition Service API")
                        .version("1.0.0")
                        .description("Tra cứu học phí theo MSSV và gạch nợ học phí."))
                .servers(List.of(new Server().url(serverUrl).description("Qua API Gateway")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT lấy từ POST /api/auth/login"))
                        .addSecuritySchemes("internalApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Api-Key")
                                .description("Khóa nội bộ, chỉ dùng cho lời gọi service-to-service")));
    }
}
