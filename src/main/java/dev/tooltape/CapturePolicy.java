package dev.tooltape;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit top-level allowlist. Nested values require separate projection by the caller. */
public final class CapturePolicy {
  public static final String REDACTED = "[REDACTED]";
  private static final Pattern SECRET =
      Pattern.compile(
          "(password|passwd|secret|token|authorization|cookie|apikey|credential|privatekey)",
          Pattern.CASE_INSENSITIVE);
  private final Set<String> inputs, outputs;

  private CapturePolicy(Set<String> inputs, Set<String> outputs) {
    this.inputs = Set.copyOf(inputs);
    this.outputs = Set.copyOf(outputs);
  }

  public static CapturePolicy safe() {
    return new CapturePolicy(Set.of(), Set.of());
  }

  public static CapturePolicy allow(Set<String> inputFields, Set<String> outputFields) {
    return new CapturePolicy(inputFields, outputFields);
  }

  /** Stable fingerprint of the capture semantics and sorted allowlists, never their values. */
  public String identifier() {
    String descriptor =
        Json.write(
            Map.of(
                "algorithm",
                "scalar-v2",
                "inputs",
                inputs.stream().sorted().toList(),
                "outputs",
                outputs.stream().sorted().toList()));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(descriptor.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required SHA-256 unavailable", impossible);
    }
  }

  public Map<String, Object> input(Map<String, Object> value) {
    return project(value, inputs);
  }

  public Map<String, Object> output(Map<String, Object> value) {
    return project(value, outputs);
  }

  private Map<String, Object> project(Map<String, Object> value, Set<String> allowed) {
    Objects.requireNonNull(value, "Tool values must be objects");
    Map<String, Object> result = new TreeMap<>();
    value.forEach(
        (k, v) -> {
          Objects.requireNonNull(k);
          if (SECRET.matcher(k.replaceAll("[^A-Za-z0-9]", "")).find()) return;
          if (allowed.contains(k)) {
            if (v == null || v instanceof String || v instanceof Boolean || v instanceof Number)
              result.put(k, v);
            else result.put(k, REDACTED);
          }
        });
    // Also validates finite numeric values before any tool side effect.
    Json.write(result);
    return result;
  }
}
