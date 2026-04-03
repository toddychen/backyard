package com.backyard.playground.controller;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Prepends /api to all @RestController mappings, excluding springdoc's
        // internal controllers. Without this exclusion, springdoc's endpoints
        // (e.g. /v3/api-docs) become /api/v3/api-docs, and the versioning
        // middleware tries to parse "v3" as an API version and rejects it.
        configurer.addPathPrefix(
                "/api",
                c -> c.isAnnotationPresent(RestController.class)
                        && !c.getPackageName().startsWith("org.springdoc"));
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        // After the /api prefix is applied, the full path is /api/{version}/...
        // so the version sits at segment index 1.
        // The default parser strips leading non-digits: "v1" → 1.
        // Predicate scopes version extraction to /api/** only — without this,
        // non-API paths like /dory/toddy would have "toddy" parsed as a version.
        // setVersionRequired(false) allows non-API handlers (e.g. DoryUiController)
        // to match without providing a version.
        configurer
                .usePathSegment(1, path -> path.value().startsWith("/api"))
                .setVersionRequired(false);
    }
}
