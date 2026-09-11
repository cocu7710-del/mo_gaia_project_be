package com.gaiaproject.mo_gaia_project_be.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * Vite 빌드 산출물(/assets/**)은 파일명 자체에 콘텐츠 해시가 붙어 나온다 — 내용이 바뀌면
 * 파일명도 바뀌므로 무한정 캐시해도 안전하다. index.html 등 해시 없는 파일은 기본 정책(캐시 안 함) 그대로 둔다.
 */
@Configuration
public class WebResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
    }
}
