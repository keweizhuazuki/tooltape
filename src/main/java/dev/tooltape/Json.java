package dev.tooltape;

import java.math.BigDecimal;
import java.util.*;

/** Small strict JSON codec. Object keys are sorted for deterministic comparisons. */
public final class Json {
  private Json() {}

  public static final int MAX_BYTES = 16 * 1024 * 1024;
  public static final int MAX_NUMBER_LENGTH = 10000;
  public static final int MAX_NODES = 100000;

  /** Canonical JSON with an incremental UTF-8 budget, including exponent expansion. */
  public static String write(Object value) {
    Writer writer = new Writer();
    writer.value(value, 0);
    return writer.out.toString();
  }

  private static final class Writer {
    final StringBuilder out = new StringBuilder();
    int bytes;
    int nodes;

    void reserve(long count) {
      if (count > MAX_BYTES - bytes)
        throw new IllegalArgumentException("Canonical JSON exceeds 16 MiB");
    }

    void append(char c) {
      int size = c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
      reserve(size);
      out.append(c);
      bytes += size;
    }

    void ascii(String text) {
      reserve(text.length());
      out.append(text);
      bytes += text.length();
    }

    void string(String text) {
      append('"');
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        switch (c) {
          case '"' -> ascii("\\\"");
          case '\\' -> ascii("\\\\");
          case '\n' -> ascii("\\n");
          case '\r' -> ascii("\\r");
          case '\t' -> ascii("\\t");
          default -> {
            if (c < 32 || Character.isSurrogate(c)) ascii(String.format("\\u%04x", (int) c));
            else append(c);
          }
        }
      }
      append('"');
    }

    void value(Object value, int depth) {
      if (++nodes > MAX_NODES)
        throw new IllegalArgumentException("JSON value count exceeds 100000");
      if (depth > 64) throw new IllegalArgumentException("JSON nesting exceeds 64");
      if (value == null) {
        ascii("null");
        return;
      }
      if (value instanceof String text) {
        string(text);
        return;
      }
      if (value instanceof Boolean) {
        ascii(value.toString());
        return;
      }
      if (value instanceof Number n) {
        String raw = n.toString();
        if (raw.length() > MAX_NUMBER_LENGTH)
          throw new IllegalArgumentException("Numeric literal exceeds 10000 characters");
        try {
          BigDecimal decimal = new BigDecimal(raw);
          if (Math.abs((long) decimal.scale()) > 10000)
            throw new IllegalArgumentException("Numeric scale exceeds 10000");
          decimal = decimal.stripTrailingZeros();
          int precision = decimal.precision(), scale = decimal.scale();
          long length =
              decimal.signum() == 0
                  ? 1
                  : scale <= 0
                      ? (long) precision - scale
                      : precision > scale ? (long) precision + 1 : (long) scale + 2;
          if (decimal.signum() < 0) length++;
          if (length > MAX_NUMBER_LENGTH)
            throw new IllegalArgumentException("Canonical number exceeds 10000 characters");
          reserve(length); // Check before toPlainString allocates zero padding.
          ascii(decimal.toPlainString());
          return;
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Non-finite or unsupported number");
        }
      }
      if (value instanceof Map<?, ?> map) {
        if (map.size() > MAX_NODES)
          throw new IllegalArgumentException("JSON value count exceeds 100000");
        TreeMap<String, Object> sorted = new TreeMap<>();
        map.forEach(
            (key, item) -> {
              if (!(key instanceof String))
                throw new IllegalArgumentException("JSON key must be string");
              sorted.put((String) key, item);
            });
        append('{');
        boolean first = true;
        for (var entry : sorted.entrySet()) {
          if (!first) append(',');
          first = false;
          string(entry.getKey());
          append(':');
          value(entry.getValue(), depth + 1);
        }
        append('}');
        return;
      }
      if (value instanceof List<?> list) {
        if (list.size() > MAX_NODES)
          throw new IllegalArgumentException("JSON value count exceeds 100000");
        append('[');
        boolean first = true;
        for (Object item : list) {
          if (!first) append(',');
          first = false;
          value(item, depth + 1);
        }
        append(']');
        return;
      }
      throw new IllegalArgumentException("Only JSON values are supported");
    }
  }

  public static Object read(String text) {
    Objects.requireNonNull(text);
    if (text.length() > MAX_BYTES
        || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("JSON input exceeds 16 MiB");
    Parser p = new Parser(text);
    Object v = p.value(0);
    p.ws();
    if (p.i != text.length()) throw p.bad();
    return v;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(String text) {
    Object v = read(text);
    if (!(v instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
    return (Map<String, Object>) v;
  }

  /** First differing RFC 6901 JSON pointer; the empty pointer denotes the root. */
  public static Optional<String> firstDifference(Object expected, Object actual) {
    return difference(expected, actual, "");
  }

  private static Optional<String> difference(Object expected, Object actual, String path) {
    if (expected instanceof Map<?, ?> a && actual instanceof Map<?, ?> b) {
      TreeSet<String> keys = new TreeSet<>();
      a.keySet().forEach(k -> keys.add((String) k));
      b.keySet().forEach(k -> keys.add((String) k));
      for (String key : keys) {
        String child = path + "/" + key.replace("~", "~0").replace("/", "~1");
        if (!a.containsKey(key) || !b.containsKey(key)) return Optional.of(child);
        Optional<String> result = difference(a.get(key), b.get(key), child);
        if (result.isPresent()) return result;
      }
      return Optional.empty();
    }
    if (expected instanceof List<?> a && actual instanceof List<?> b) {
      for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
        Optional<String> result = difference(a.get(i), b.get(i), path + "/" + i);
        if (result.isPresent()) return result;
      }
      return a.size() == b.size()
          ? Optional.empty()
          : Optional.of(path + "/" + Math.min(a.size(), b.size()));
    }
    return write(expected).equals(write(actual)) ? Optional.empty() : Optional.of(path);
  }

  private static final class Parser {
    final String s;
    int i;
    int nodes;

    Parser(String s) {
      this.s = Objects.requireNonNull(s);
    }

    IllegalArgumentException bad() {
      return new IllegalArgumentException("Invalid JSON at offset " + i);
    }

    void ws() {
      while (i < s.length() && " \n\r\t".indexOf(s.charAt(i)) >= 0) i++;
    }

    boolean take(char c) {
      ws();
      if (i < s.length() && s.charAt(i) == c) {
        i++;
        return true;
      }
      return false;
    }

    Object value(int depth) {
      if (++nodes > MAX_NODES)
        throw new IllegalArgumentException("JSON value count exceeds 100000");
      if (depth > 64) throw new IllegalArgumentException("JSON nesting exceeds 64");
      ws();
      if (i >= s.length()) throw bad();
      char c = s.charAt(i);
      if (c == '"') return string();
      if (take('{')) {
        Map<String, Object> m = new TreeMap<>();
        if (take('}')) return m;
        do {
          ws();
          if (i >= s.length() || s.charAt(i) != '"') throw bad();
          String k = string();
          if (!take(':')) throw bad();
          if (m.containsKey(k)) throw bad();
          m.put(k, value(depth + 1));
        } while (take(','));
        if (!take('}')) throw bad();
        return m;
      }
      if (take('[')) {
        List<Object> l = new ArrayList<>();
        if (take(']')) return l;
        do {
          l.add(value(depth + 1));
        } while (take(','));
        if (!take(']')) throw bad();
        return l;
      }
      for (String literal : List.of("true", "false", "null"))
        if (s.startsWith(literal, i)) {
          i += literal.length();
          return literal.equals("null") ? null : literal.equals("true");
        }
      int start = i;
      while (i < s.length() && "0123456789eE+-.".indexOf(s.charAt(i)) >= 0) i++;
      if (i - start > MAX_NUMBER_LENGTH)
        throw new IllegalArgumentException("Numeric literal exceeds 10000 characters");
      String n = s.substring(start, i);
      if (!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw bad();
      try {
        BigDecimal number = new BigDecimal(n);
        if (Math.abs((long) number.scale()) > 10000) throw bad();
        return number;
      } catch (NumberFormatException e) {
        throw bad();
      }
    }

    String string() {
      i++;
      StringBuilder b = new StringBuilder();
      while (i < s.length()) {
        char c = s.charAt(i++);
        if (c == '"') return b.toString();
        if (c < 32) throw bad();
        if (c != '\\') {
          b.append(c);
          continue;
        }
        if (i >= s.length()) throw bad();
        char e = s.charAt(i++);
        switch (e) {
          case '"', '\\', '/' -> b.append(e);
          case 'b' -> b.append('\b');
          case 'f' -> b.append('\f');
          case 'n' -> b.append('\n');
          case 'r' -> b.append('\r');
          case 't' -> b.append('\t');
          case 'u' -> {
            if (i + 4 > s.length()) throw bad();
            try {
              String hex = s.substring(i, i + 4);
              if (!hex.matches("[0-9A-Fa-f]{4}")) throw bad();
              b.append((char) Integer.parseInt(hex, 16));
            } catch (NumberFormatException ex) {
              throw bad();
            }
            i += 4;
          }
          default -> throw bad();
        }
      }
      throw bad();
    }
  }
}
