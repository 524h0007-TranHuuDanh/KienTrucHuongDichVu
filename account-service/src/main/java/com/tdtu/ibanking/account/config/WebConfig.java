package com.tdtu.ibanking.account.config;

import com.tdtu.ibanking.account.security.InternalApiKeyFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Đăng ký InternalApiKeyFilter chỉ cho /api/account/* - account-service không dùng
 * Spring Security (không có SecurityFilterChain) nên đăng ký trực tiếp qua
 * FilterRegistrationBean thay vì http.addFilterBefore(...) như auth-service.
 * /actuator/health và /swagger-ui/**, /v3/api-docs/** không bị chặn.
 */
@Configuration
public class WebConfig {

    /**
     * Bug đã sửa (phát hiện lúc kiểm chứng Docker thật, Phase 6): trước đây filter được
     * tạo bằng "new InternalApiKeyFilter()" trực tiếp bên trong thân của
     * internalApiKeyFilterRegistration() — Spring chỉ post-process (bao gồm @Value) đối
     * tượng THỰC SỰ được @Bean trả về, không "chui vào" tạo instance lồng bên trong như vậy.
     * Do đó field internalApiKey luôn null, constantTimeEquals(key, null) luôn false ->
     * MỌI request bị từ chối 403 dù key đúng (silent-fail: log seed vẫn "seeded" vì
     * DemoDataSeeder chỉ log warning). Khai filter là @Bean riêng (giống cách
     * auth-service/config/SecurityConfig làm) để @Value được Spring xử lý đúng.
     */
    @Bean
    public InternalApiKeyFilter internalApiKeyFilter() {
        return new InternalApiKeyFilter();
    }

    @Bean
    public FilterRegistrationBean<InternalApiKeyFilter> internalApiKeyFilterRegistration(
            InternalApiKeyFilter internalApiKeyFilter) {
        FilterRegistrationBean<InternalApiKeyFilter> registration =
                new FilterRegistrationBean<>(internalApiKeyFilter);
        registration.addUrlPatterns("/api/account/*");
        registration.setOrder(1);
        return registration;
    }
}
