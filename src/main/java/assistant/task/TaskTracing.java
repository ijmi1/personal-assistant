package assistant.task;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.HashMap;

@Component
public class TaskTracing {
    private final Tracer tracer;
    private final Propagator propagator;

    TaskTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable();
        this.propagator = propagator.getIfAvailable();
    }

    /** Captures W3C headers only. A null link is valid for untraced and previously queued tasks. */
    public Link capture() {
        if (tracer == null || propagator == null || tracer.currentSpan() == null) {
            return null;
        }
        var headers = new HashMap<String, String>();
        propagator.inject(tracer.currentSpan().context(), headers, HashMap::put);
        return new Link(headers.get("traceparent"), headers.get("tracestate"));
    }

    void run(Link link, String name, Runnable work) {
        if (tracer == null || propagator == null) {
            work.run();
            return;
        }
        Span span = link == null || link.traceparent() == null ? tracer.nextSpan().name(name).start()
                : propagator.extract(link, Link::header).name(name).start();
        try (var scope = tracer.withSpan(span)) {
            work.run();
        } catch (RuntimeException error) {
            span.error(error);
            throw error;
        } finally {
            span.end();
        }
    }

    public record Link(String traceparent, String tracestate) implements Serializable {
        private static final long serialVersionUID = 1L;

        private String header(String name) {
            return switch (name) {
                case "traceparent" -> traceparent;
                case "tracestate" -> tracestate;
                default -> null;
            };
        }
    }
}
