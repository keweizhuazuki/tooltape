package dev.tooltape;

import java.util.Map;

/**
 * Serial application boundary using JSON-compatible argument/result objects.
 *
 * <p>Do not perform side effects outside this boundary during replay. A {@link Recorder} returns
 * the delegate's original result; {@link Replay} returns a newly parsed, sanitized JSON object.
 * Replayed numbers are {@link java.math.BigDecimal}, not the original Java numeric wrapper type.
 * Normalize JSON values into domain types instead of casting results to {@code Integer} or
 * comparing maps by Java wrapper equality. Exact integral conversions should reject fractions and
 * overflow rather than silently truncate them.
 */
@FunctionalInterface
public interface Tool {
  Map<String, Object> call(String name, Map<String, Object> arguments) throws Exception;
}
