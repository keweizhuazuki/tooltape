package dev.tooltape;

import java.util.*;
import java.util.regex.Pattern;

/** Explicit top-level allowlist. Nested values require separate projection by the caller. */
public final class CapturePolicy {
    public static final String REDACTED = "[REDACTED]";
    private static final Pattern SECRET = Pattern.compile(".*(password|passwd|secret|token|authorization|cookie|api.?key|credential|private.?key).*", Pattern.CASE_INSENSITIVE);
    private final Set<String> inputs, outputs;
    private CapturePolicy(Set<String> inputs, Set<String> outputs) { this.inputs=Set.copyOf(inputs); this.outputs=Set.copyOf(outputs); }
    public static CapturePolicy safe() { return new CapturePolicy(Set.of(),Set.of()); }
    public static CapturePolicy allow(Set<String> inputFields, Set<String> outputFields) { return new CapturePolicy(inputFields,outputFields); }
    public Map<String,Object> input(Map<String,Object> value) {return project(value, inputs);}
    public Map<String,Object> output(Map<String,Object> value) {return project(value, outputs);}
    private Map<String,Object> project(Map<String,Object> value, Set<String> allowed) {
        Objects.requireNonNull(value,"Tool values must be objects"); Map<String,Object> result=new TreeMap<>();
        value.forEach((k,v)-> { Objects.requireNonNull(k); if(SECRET.matcher(k).matches()) return;
            if(allowed.contains(k)) { if(v==null || v instanceof String || v instanceof Boolean || v instanceof Number) result.put(k,v); else result.put(k,REDACTED); }
        });
        // Also validates finite numeric values before any tool side effect.
        Json.write(result); return result;
    }
}
