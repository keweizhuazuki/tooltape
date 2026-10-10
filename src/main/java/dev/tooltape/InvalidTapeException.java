package dev.tooltape;

import java.io.IOException;

/** Invalid recording content or format limits, distinct from a filesystem read failure. */
public final class InvalidTapeException extends IOException {
  private static final long serialVersionUID = 1L;

  public InvalidTapeException(String message) {
    super(message);
  }

  public InvalidTapeException(String message, Throwable cause) {
    super(message, cause);
  }
}
