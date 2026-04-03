package com.backyard.playground.client.error;

import com.backyard.playground.exception.BadRequestException;
import com.backyard.playground.exception.ForbiddenException;
import com.backyard.playground.exception.NotFoundException;
import com.backyard.playground.exception.ServiceUnavailableException;
import com.backyard.playground.exception.UnauthorizedException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared status handler for RestClient — maps upstream HTTP error codes to
 * typed exceptions that {@code GlobalExceptionHandler} converts to clean API
 * responses. Used as a method reference in each client config:
 * {@code .defaultStatusHandler(HttpStatusCode::isError,
 * ClientStatusHandlers::handleError)}.
 */
public final class ClientStatusHandlers {

    private ClientStatusHandlers() {
    }

    public static void handleError(HttpRequest request, ClientHttpResponse response)
            throws IOException {
        int status = response.getStatusCode().value();
        byte[] body = response.getBody().readAllBytes();
        String message = String.format(
                "[%d %s] during [%s] to [%s]: [%s]",
                status,
                response.getStatusText(),
                request.getMethod(),
                request.getURI(),
                new String(body, StandardCharsets.UTF_8));
        RestClientResponseException cause = new RestClientResponseException(
                message,
                response.getStatusCode(),
                response.getStatusText(),
                response.getHeaders(),
                body,
                StandardCharsets.UTF_8);
        throw switch (status) {
        case 400 -> new BadRequestException("upstream bad request", cause);
        case 401 -> new UnauthorizedException("upstream authentication failed", cause);
        case 403 -> new ForbiddenException("upstream access denied", cause);
        case 404 -> new NotFoundException("upstream not found", cause);
        case 429 -> new ServiceUnavailableException("upstream rate limit exceeded", cause);
        case 503 -> new ServiceUnavailableException("upstream service unavailable", cause);
        default -> new ServiceUnavailableException("upstream error " + status, cause);
        };
    }
}
