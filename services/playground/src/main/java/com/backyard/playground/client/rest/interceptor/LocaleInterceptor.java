package com.backyard.playground.client.rest.interceptor;

import com.backyard.playground.context.ClientLocaleContext;
import com.backyard.playground.context.ClientLocaleContextHolder;
import com.backyard.playground.data.model.locale.SupportedLocaleDTO;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.function.Function;

/**
 * Adds an {@code Accept-Language} header to every outbound request, mapping
 * the current request's resolved {@link SupportedLocaleDTO} to the tag the
 * upstream API accepts.
 *
 * <p>
 * The locale is read from {@link ClientLocaleContextHolder}, which is
 * populated by {@code LocaleFilter} on the request thread. Because
 * {@link ClientLocaleContextHolder} uses {@link InheritableThreadLocal}, this
 * interceptor works correctly even when the client call is made from a virtual
 * thread (e.g. inside a {@code FanOut} parallel call) — the locale is
 * inherited automatically.
 *
 * <p>
 * Use the static factory methods to create an instance with the appropriate
 * locale mapping for each upstream client:
 *
 * <pre>
 *   // Yahoo Sports — maps to en-US / es-US
 *   new LocaleInterceptor(YahooSportsLocaleDTO::fromServiceLocale)
 *
 *   // Client that accepts standard BCP 47 tags directly
 *   new LocaleInterceptor(SupportedLocaleDTO::getLanguageTag)
 * </pre>
 */
public class LocaleInterceptor implements ClientHttpRequestInterceptor {

    private final Function<SupportedLocaleDTO, String> mapper;

    /**
     * @param mapper converts the resolved {@link SupportedLocaleDTO} to the
     *               BCP 47 tag the upstream API accepts
     */
    public LocaleInterceptor(Function<SupportedLocaleDTO, String> mapper) {
        this.mapper = mapper;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientLocaleContext ctx = ClientLocaleContextHolder.get();
        if (ctx != null) {
            request.getHeaders()
                    .set(HttpHeaders.ACCEPT_LANGUAGE, mapper.apply(ctx.supportedLocale()));
        }
        return execution.execute(request, body);
    }
}
