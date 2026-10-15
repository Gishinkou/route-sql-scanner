package com.acme.routesql.util;

import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Builds the stable, versioned identity emitted for each extracted SQL row. */
public final class SqlKey {
  public static final String VERSION = "v1";
  private static final String PREFIX = VERSION + ":";

  private SqlKey() {}

  public static String of(Path projectRoot, SqlObject sqlObject) {
    if (sqlObject == null) {
      throw new IllegalArgumentException("sqlObject must not be null");
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream canonical = new DataOutputStream(bytes);
      SqlOrigin origin = sqlObject.origin();
      write(canonical, origin == null || origin.kind() == null ? null : origin.kind().name());
      write(canonical, stablePath(projectRoot, origin == null ? null : origin.file()));
      write(canonical, origin == null ? null : origin.namespace());
      write(canonical, origin == null ? null : origin.statementId());
      write(canonical, origin == null ? null : origin.statementType());
      write(canonical, origin == null ? null : origin.className());
      write(canonical, origin == null ? null : origin.methodName());
      write(canonical, canonicalSql(sqlObject.rawSql()));
      canonical.writeBoolean(sqlObject.dynamic());
      canonical.flush();

      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
      return PREFIX + hex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("cannot generate sqlKey", e);
    }
  }

  public static String sourceStatementId(Path projectRoot, SqlObject sqlObject) {
    if (sqlObject == null) {
      throw new IllegalArgumentException("sqlObject must not be null");
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream canonical = new DataOutputStream(bytes);
      SqlOrigin origin = sqlObject.origin();
      write(canonical, origin == null || origin.kind() == null ? null : origin.kind().name());
      write(canonical, stablePath(projectRoot, origin == null ? null : origin.file()));
      write(canonical, origin == null ? null : origin.namespace());
      write(canonical, origin == null ? null : origin.statementId());
      write(canonical, origin == null ? null : origin.statementType());
      write(canonical, origin == null ? null : origin.className());
      write(canonical, origin == null ? null : origin.methodName());
      canonical.flush();
      return "source-v1:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (Exception e) {
      throw new IllegalStateException("cannot generate sourceStatementId", e);
    }
  }

  public static boolean isValid(String key) {
    return key != null && key.matches("^" + VERSION + ":[0-9a-f]{64}$");
  }

  private static String stablePath(Path projectRoot, Path file) {
    String relative = OriginPaths.sourcePath(projectRoot, file);
    if (!Strings.isBlank(relative)) {
      return relative;
    }
    if (file == null) {
      return null;
    }
    // A caller that needs checkout-independent keys should supply projectRoot. This fallback preserves
    // deterministic behavior for the reporter's programmatic no-root constructor.
    return file.toAbsolutePath().normalize().toString().replace('\\', '/');
  }

  private static String canonicalSql(String sql) {
    return sql == null ? null : sql.replaceAll("\\s+", " ").trim();
  }

  private static String hex(byte[] digest) {
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hex.append(String.format("%02x", b & 0xff));
    }
    return hex.toString();
  }

  private static void write(DataOutputStream out, String value) throws Exception {
    if (value == null) {
      out.writeInt(-1);
      return;
    }
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(encoded.length);
    out.write(encoded);
  }
}
