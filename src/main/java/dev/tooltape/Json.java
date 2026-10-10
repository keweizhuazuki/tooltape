package dev.tooltape;

import java.math.BigDecimal;
import java.util.*;

/** Small strict JSON codec. Object keys are sorted for deterministic comparisons. */
public final class Json {
  private Json() {}

  public static String write(Object value) {
    if (value == null) return "null";
    if (value instanceof String s) {
      StringBuilder b = new StringBuilder("\"");
      for (char c : s.toCharArray()) {
        switch (c) {
          case '"' -> b.append("\\\"");
          case '\\' -> b.append("\\\\");
          case '\n' -> b.append("\\n");
          case '\r' -> b.append("\\r");
          case '\t' -> b.append("\\t");
          default -> {
            if (c < 32 || Character.isSurrogate(c)) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
          }
        }
      }
      return b.append('"').toString();
    }
    if (value instanceof Boolean) return value.toString();
    if (value instanceof Number n) {
      try {
        return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Non-finite or unsupported number");
      }
    }
    if (value instanceof Map<?, ?> map) {
      TreeMap<String, Object> sorted = new TreeMap<>();
      map.forEach(
          (k, v) -> {
            if (!(k instanceof String))
              throw new IllegalArgumentException("JSON key must be string");
            sorted.put((String) k, v);
          });
      StringJoiner j = new StringJoiner(",", "{", "}");
      sorted.forEach((k, v) -> j.add(write(k) + ":" + write(v)));
      return j.toString();
    }
    if (value instanceof List<?> list) {
      StringJoiner j = new StringJoiner(",", "[", "]");
      list.forEach(v -> j.add(write(v)));
      return j.toString();
    }
    throw new IllegalArgumentException("Only JSON values are supported");
  }

  public static Object read(String text) {
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
              b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
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
