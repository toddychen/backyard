package com.backyard.playground.context;

/**
 * Static holder for the {@link ClientLocaleContext} of the current request,
 * following Spring's own {@code XxxContextHolder} pattern (e.g.
 * {@code LocaleContextHolder}, {@code
 * SecurityContextHolder}).
 *
 * <p>
 * Future request-scoped contexts (e.g. authenticated user, device info) should
 * follow the same pattern in this package: a value record + a static holder.
 *
 * <h3>Why InheritableThreadLocal?</h3>
 *
 * <p>
 * A plain {@link ThreadLocal} is invisible to child threads — each thread
 * starts with a blank slate. This service uses
 * {@link com.backyard.playground.tools.FanOut} to dispatch parallel work to
 * virtual threads. Without propagation, those virtual threads would see
 * {@code null} for the locale context, causing cache key misses and incorrect
 * {@code Accept-Language} headers on upstream calls.
 *
 * <p>
 * {@link InheritableThreadLocal} copies the parent thread's value into any
 * child thread at the moment the child is created. In Java 21, virtual threads
 * inherit from the thread that <em>submitted</em> the task (the Tomcat request
 * thread), not from the carrier platform thread. So when {@code FanOut} submits
 * tasks via {@code Executors.newVirtualThreadPerTaskExecutor()}, each virtual
 * thread automatically receives a copy of the request's
 * {@link ClientLocaleContext} with no extra wiring in {@code FanOut}.
 *
 * <h3>Lifecycle</h3>
 *
 * <p>
 * {@link com.backyard.playground.filter.LocaleFilter} calls {@link #set} before
 * the filter chain and {@link #clear} in the {@code finally} block, ensuring no
 * context leaks between requests.
 */
public class ClientLocaleContextHolder {

    // InheritableThreadLocal propagates the value to virtual threads
    // spawned by FanOut. See class Javadoc for the full explanation.
    private static final InheritableThreadLocal<ClientLocaleContext> HOLDER = new InheritableThreadLocal<>();

    private ClientLocaleContextHolder() {
    }

    public static void set(ClientLocaleContext ctx) {
        HOLDER.set(ctx);
    }

    /** Returns the current context, or {@code null} when not set. */
    public static ClientLocaleContext get() {
        return HOLDER.get();
    }

    /** Removes the context. Must be called in a {@code finally} block. */
    public static void clear() {
        HOLDER.remove();
    }
}
