package io.rbgs.api.matchmaking;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/** Test-only measurement of the SELECT FOR UPDATE round trips inside HTTP requests. */
@TestConfiguration(proxyBeanMethods = false)
class QueueCoordinatorTimings implements BeanPostProcessor {
    private final ThreadLocal<Sample> current = new ThreadLocal<>();
    private final ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
    private volatile String phase;

    void measure(String phase) { this.phase = phase; }

    Map<String, Object> report() {
        var result = new java.util.TreeMap<String, Object>();
        samples.stream().map(Sample::phase).distinct().forEach(phase -> {
            List<Sample> selected = samples.stream().filter(sample -> sample.phase.equals(phase)).toList();
            result.put(phase, Map.of(
                    "fullServer", stats(selected.stream().map(sample -> sample.totalNs).toList()),
                    "coordinatorSql", stats(selected.stream().map(Sample::coordinatorNs).toList()),
                    "remainingServer", stats(selected.stream().map(sample -> sample.totalNs - sample.coordinatorNs).toList())));
        });
        return result;
    }

    static Map<String, Number> stats(List<Long> times) {
        var sorted = times.stream().sorted().toList();
        return Map.of("samples", sorted.size(),
                "p95Ms", Math.round(sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) / 1_000_000.0));
    }

    @Bean
    FilterRegistrationBean<Filter> queueCoordinatorTimingFilter() {
        Filter filter = (request, response, chain) -> {
            var http = (HttpServletRequest) request;
            String label = phase;
            if (label == null || !http.getMethod().equals("POST")) {
                chain.doFilter(request, response);
                return;
            }
            var sample = new Sample(label);
            current.set(sample);
            long started = System.nanoTime();
            try {
                chain.doFilter(request, response);
            } finally {
                current.remove();
                sample.totalNs = System.nanoTime() - started;
                samples.add(sample);
            }
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/v1/me/solo-queue", "/api/v1/me/solo-queue/rooms/*");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof QueueTicketRepository && bean instanceof Advised repository) {
            repository.addAdvice(0, (MethodInterceptor) invocation -> {
                var sample = current.get();
                if (sample == null || !invocation.getMethod().getName().equals("lockCoordinator"))
                    return invocation.proceed();
                long started = System.nanoTime();
                try {
                    return invocation.proceed();
                } finally {
                    sample.coordinatorNs += System.nanoTime() - started;
                }
            });
        }
        return bean;
    }

    private static final class Sample {
        private final String phase;
        private long coordinatorNs;
        private long totalNs;

        private Sample(String phase) { this.phase = phase; }
        String phase() { return phase; }
        long coordinatorNs() { return coordinatorNs; }
    }
}
