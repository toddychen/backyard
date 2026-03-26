package com.backyard.playground.controller;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Prepends /api to all @RestController mappings globally — controllers
        // declare only /{version}/resource without the /api prefix.
        configurer.addPathPrefix("/api",
                c -> c.isAnnotationPresent(RestController.class));
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        // After the /api prefix is applied, the full path is /api/{version}/...
        // so the version sits at segment index 1.
        // The default parser strips leading non-digits: "v1" → 1.
        configurer.usePathSegment(1);
    }
}
