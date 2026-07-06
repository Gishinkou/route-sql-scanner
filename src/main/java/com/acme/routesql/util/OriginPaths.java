package com.acme.routesql.util;

import java.nio.file.Path;
import java.util.List;

public final class OriginPaths {
  private static final List<String> CLASSPATH_ANCHORS = List.of(
      "src/main/resources/",
      "src/test/resources/",
      "src/main/java/",
      "src/test/java/"
  );

  private OriginPaths() {}

  /** Project-root-relative path with forward slashes, or null when file is not under root. */
  public static String sourcePath(Path projectRoot, Path file) {
    if (projectRoot == null || file == null) {
      return null;
    }
    Path root = projectRoot.toAbsolutePath().normalize();
    Path abs = file.toAbsolutePath().normalize();
    if (!abs.startsWith(root)) {
      return null;
    }
    return toSlash(root.relativize(abs).toString());
  }

  /**
   * Classpath-relative path (everything after a src/main/resources|java anchor), or null when no
   * anchor is present. Prefers sourcePath as input so it works relative to the project root.
   */
  public static String resourcePath(String sourcePath) {
    if (sourcePath == null || sourcePath.isBlank()) {
      return null;
    }
    String slashed = toSlash(sourcePath);
    for (String anchor : CLASSPATH_ANCHORS) {
      int idx = slashed.indexOf(anchor);
      if (idx >= 0) {
        return slashed.substring(idx + anchor.length());
      }
    }
    return null;
  }

  private static String toSlash(String path) {
    return path.replace('\\', '/');
  }
}
