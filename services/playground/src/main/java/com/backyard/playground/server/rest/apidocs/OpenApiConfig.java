package com.backyard.playground.server.rest.apidocs;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class OpenApiConfig {

    // Versioning limitation: springdoc does not understand the Spring
    // Framework 7 `version = "1+"` attribute on @GetMapping/@PostMapping
    // etc. It treats /{version} as a literal path parameter and ignores
    // the version constraint entirely.
    //
    // The server base URL below (/api/v1) is a workaround that keeps
    // spec paths clean (/dory/reminders, /sport/..., etc.) for the v1
    // case. It works because all current endpoints use version = "1+".
    //
    // If a v2 endpoint is added, springdoc will not automatically split
    // the spec. Options at that point:
    // - Use @GroupedOpenApi beans (one per version, each with its own
    // server URL and path/package filter) to produce separate specs
    // at /v3/api-docs/v1 and /v3/api-docs/v2.
    // - Document version differences manually via @Operation descriptions
    // and keep a single combined spec.
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Playground API")
                                .version("v1")
                                .description("Internal playground service"))
                .servers(List.of(new Server().url("/api/v1").description("v1")));
    }

    // springdoc generates paths with the /api prefix and resolved version
    // segment, e.g. /api/1/echo, /api/1/dory/reminders. Combined with the
    // /api/v1 server URL, Swagger UI constructs /api/v1/api/1/echo —
    // doubling the prefix.
    //
    // This customizer strips the leading /api/<version> segment from every
    // generated path so the spec contains clean paths (/echo,
    // /dory/reminders, ...) that resolve correctly against the /api/v1
    // server base URL.
    @Bean
    public OpenApiCustomizer stripVersionPrefix() {
        return openApi -> {
            Paths paths = openApi.getPaths();
            if (paths == null)
                return;
            Map<String, PathItem> rewritten = new LinkedHashMap<>();
            paths.forEach(
                    (path, item) -> rewritten.put(path.replaceFirst("^/api/[^/]+", ""), item));
            Paths clean = new Paths();
            rewritten.forEach(clean::addPathItem);
            openApi.setPaths(clean);
        };
    }
}
