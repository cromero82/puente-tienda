package com.infinitesoft.puente_tienda.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${puente.store-key:change-me-pos-email-inbound}")
    private String storeKey;

    private final ApiRequestLogInterceptor apiRequestLogInterceptor;

    public WebConfig(ApiRequestLogInterceptor apiRequestLogInterceptor) {
        this.apiRequestLogInterceptor = apiRequestLogInterceptor;
    }

    public String getStoreKey() {
        return storeKey;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiRequestLogInterceptor).addPathPatterns("/api/**");
    }
}
