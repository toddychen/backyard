package com.backyard.playground.server.grpc.interceptor;

import com.backyard.playground.context.ClientLocaleContext;
import com.backyard.playground.context.ClientLocaleContextHolder;
import com.backyard.playground.context.ClientLocaleResolver;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * gRPC counterpart of {@code LocaleFilter}.
 *
 * <p>
 * Reads the {@code accept-language} metadata key, resolves it via
 * {@link ClientLocaleResolver}, and stores the result in
 * {@link ClientLocaleContextHolder} for the duration of the call. Clears both
 * the holder and MDC in {@code onComplete} and {@code onCancel}.
 */
@Component
public class GrpcLocaleInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> ACCEPT_LANGUAGE = Metadata.Key.of("accept-language",
            Metadata.ASCII_STRING_MARSHALLER);

    public static final String MDC_LOCALE = "locale";

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next) {
        String header = headers.get(ACCEPT_LANGUAGE);
        ClientLocaleContext ctx = ClientLocaleResolver.buildContext(header);
        ClientLocaleContextHolder.set(ctx);
        MDC.put(MDC_LOCALE, ctx.supportedLanguageTag());

        return new SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
            @Override
            public void onComplete() {
                try {
                    super.onComplete();
                } finally {
                    clear();
                }
            }

            @Override
            public void onCancel() {
                try {
                    super.onCancel();
                } finally {
                    clear();
                }
            }
        };
    }

    private static void clear() {
        ClientLocaleContextHolder.clear();
        MDC.remove(MDC_LOCALE);
    }
}
