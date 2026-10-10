package dev.tooltape;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An explicit UTF-8 fixture inventory; it does not discover files or confine filesystem access. */
record BatchManifest(Path directory, List<String> files) {
  static final int MAX_FILES = 64;
  private static final int MAX_BYTES = 64 * 1024;
  private static final int MAX_PATH_UNITS = 4096;

  BatchManifest {
    files = List.copyOf(files);
  }

  static BatchManifest read(String filename) throws IOException {
    // Reject explicit UNC/device namespaces before even a filesystem attribute lookup.
    // Mounted drives and symlinks remain ordinary filesystem inputs, not a network sandbox.
    if (filename.length() >= 2
        && isSeparator(filename.charAt(0))
        && isSeparator(filename.charAt(1)))
      throw new InvalidPathException(filename, "Unsupported manifest path");
    Path path = Path.of(filename);
    if (!Files.isRegularFile(path)) throw new IOException("Manifest is not a regular file");
    byte[] bytes;
    try (InputStream in = Files.newInputStream(path)) {
      bytes = in.readNBytes(MAX_BYTES + 1);
    }
    if (bytes.length > MAX_BYTES) throw invalid();
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid();
    }
    // A single leading BOM accommodates UTF-8 files written by Windows editors/PowerShell.
    if (text.startsWith("\ufeff")) text = text.substring(1);
    Map<String, Object> manifest = Json.object(text);
    if (!manifest.keySet().equals(Set.of("manifestVersion", "files"))
        || !(manifest.get("manifestVersion") instanceof BigDecimal version)
        || version.compareTo(BigDecimal.ONE) != 0
        || !(manifest.get("files") instanceof List<?> entries)
        || entries.isEmpty()
        || entries.size() > MAX_FILES) throw invalid();
    List<String> files = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof String file) || !validPath(file)) throw invalid();
      files.add(file);
    }
    // Keep the supplied location, including symlinks and '..'; do not normalize or use realpath.
    return new BatchManifest(path.toAbsolutePath().getParent(), files);
  }

  private static boolean validPath(String file) {
    if (file.isEmpty() || file.length() > MAX_PATH_UNITS || file.startsWith("/")) return false;
    for (int i = 0; i < file.length(); i++) {
      char c = file.charAt(i);
      if (c == '\\' || c == ':' || Character.isISOControl(c)) return false;
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= file.length() || !Character.isLowSurrogate(file.charAt(++i))) return false;
      } else if (Character.isLowSurrogate(c)) return false;
    }
    return true;
  }

  private static boolean isSeparator(char c) {
    return c == '/' || c == '\\';
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Manifest is malformed or unsupported.");
  }
}
