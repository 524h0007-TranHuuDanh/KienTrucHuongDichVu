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
     * Filter phải là @Bean riêng chứ không được "new" bên trong hàm đăng ký bên dưới:
     * Spring chỉ inject @Value cho đối tượng do @Bean trả về, nên instance tạo lồng
     * sẽ có internalApiKey null và từ chối 403 mọi request dù key gửi lên đúng.
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
