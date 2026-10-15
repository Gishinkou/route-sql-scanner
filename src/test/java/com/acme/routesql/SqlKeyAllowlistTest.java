package com.acme.routesql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.acme.routesql.report.CompactInventoryReporter;
import com.acme.routesql.util.SqlKey;
import com.acme.routesql.util.SqlKeyAllowlist;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlKeyAllowlistTest {
  @TempDir Path tempDir;

  @Test
  void emitsStableVersionedKeyIndependentOfCheckoutRoot() throws Exception {
    SqlObject first = sql(tempDir.resolve("checkout-a/src/main/resources/OrderMapper.xml"),
        "find", " SELECT  *\nFROM orders ");
    SqlObject second = sql(tempDir.resolve("checkout-b/src/main/resources/OrderMapper.xml"),
        "find", "SELECT * FROM orders");

    String firstKey = SqlKey.of(tempDir.resolve("checkout-a"), first);
    String secondKey = SqlKey.of(tempDir.resolve("checkout-b"), second);

    assertEquals(firstKey, secondKey);
    assertTrue(SqlKey.isValid(firstKey));
    assertTrue(firstKey.startsWith("v1:"));
  }

  @Test
  void annotationKeyIsCheckoutIndependentAndDistinguishesClassMethodAndSource() {
    Path rootA = tempDir.resolve("checkout-a");
    Path rootB = tempDir.resolve("checkout-b");
    SqlObject first = annotationSql(rootA.resolve("src/main/java/com/acme/OrderMapper.java"),
        "com.acme.OrderMapper", "find", "SELECT * FROM orders");
    SqlObject relocated = annotationSql(rootB.resolve("src/main/java/com/acme/OrderMapper.java"),
        "com.acme.OrderMapper", "find", "SELECT * FROM orders");

    String key = SqlKey.of(rootA, first);
    assertEquals(key, SqlKey.of(rootB, relocated));
    assertNotEquals(key, SqlKey.of(rootA, annotationSql(first.origin().file(),
        "com.acme.OtherMapper", "find", first.rawSql())));
    assertNotEquals(key, SqlKey.of(rootA, annotationSql(first.origin().file(),
        "com.acme.OrderMapper", "findOther", first.rawSql())));
    assertNotEquals(key, SqlKey.of(rootA, annotationSql(
        rootA.resolve("src/main/java/com/acme/copy/OrderMapper.java"),
        "com.acme.OrderMapper", "find", first.rawSql())));
  }

  @Test
  void jdbcKeyIsCheckoutIndependentAndDistinguishesClassMethodAndSource() {
    Path rootA = tempDir.resolve("checkout-a");
    Path rootB = tempDir.resolve("checkout-b");
    SqlObject first = jdbcSql(rootA.resolve("src/main/java/com/acme/OrderDao.java"),
        "com.acme.OrderDao", "find", "SELECT * FROM orders");
    SqlObject relocated = jdbcSql(rootB.resolve("src/main/java/com/acme/OrderDao.java"),
        "com.acme.OrderDao", "find", "SELECT * FROM orders");

    String key = SqlKey.of(rootA, first);
    assertEquals(key, SqlKey.of(rootB, relocated));
    assertNotEquals(key, SqlKey.of(rootA, jdbcSql(first.origin().file(),
        "com.acme.OtherDao", "find", first.rawSql())));
    assertNotEquals(key, SqlKey.of(rootA, jdbcSql(first.origin().file(),
        "com.acme.OrderDao", "findOther", first.rawSql())));
    assertNotEquals(key, SqlKey.of(rootA, jdbcSql(
        rootA.resolve("src/main/java/com/acme/copy/OrderDao.java"),
        "com.acme.OrderDao", "find", first.rawSql())));
  }

  @Test
  void sourceKindSeparatesOtherwiseIdenticalAnnotationAndJdbcIdentity() {
    Path file = tempDir.resolve("src/main/java/com/acme/OrderAccessor.java");
    SqlObject annotation = annotationSql(file, "com.acme.OrderAccessor", "find", "SELECT 1");
    SqlObject jdbc = jdbcSql(file, "com.acme.OrderAccessor", "find", "SELECT 1");

    assertNotEquals(SqlKey.of(tempDir, annotation), SqlKey.of(tempDir, jdbc));
  }

  @Test
  void sourceStatementIdIgnoresSqlTextButKeepsSourceIdentity() {
    Path file = tempDir.resolve("src/main/resources/OrderMapper.xml");
    SqlObject first = sql(file, "find", "SELECT * FROM orders");
    SqlObject changedSql = sql(file, "find", "SELECT id FROM orders WHERE id = ?");
    SqlObject otherStatement = sql(file, "findOther", "SELECT * FROM orders");

    assertEquals(SqlKey.sourceStatementId(tempDir, first),
        SqlKey.sourceStatementId(tempDir, changedSql));
    assertNotEquals(SqlKey.sourceStatementId(tempDir, first),
        SqlKey.sourceStatementId(tempDir, otherStatement));
    assertNotEquals(SqlKey.of(tempDir, first), SqlKey.of(tempDir, changedSql));
  }

  @Test
  void compactReporterEmitsSqlKeyForEveryRow() throws Exception {
    SqlObject sql = sql(tempDir.resolve("src/main/resources/OrderMapper.xml"),
        "find", "SELECT * FROM orders");
    ScanReport report = new ScanReport("acme", Collections.singletonList(sql));

    JsonNode row = new ObjectMapper().readTree(
        new CompactInventoryReporter(tempDir).render(report)).path("sqls").get(0);

    assertEquals(SqlKey.of(tempDir, sql), row.path("sqlKey").asText());
    assertEquals(SqlKey.sourceStatementId(tempDir, sql), row.path("sourceStatementId").asText());
  }

  @Test
  void filtersAfterExtractionAndPreservesExtractionOrder() throws Exception {
    SqlObject first = sql(tempDir.resolve("OrderMapper.xml"), "first", "SELECT 1");
    SqlObject second = sql(tempDir.resolve("OrderMapper.xml"), "second", "SELECT 2");
    ScanReport report = new ScanReport("acme", Arrays.asList(first, second));
    Path allowlist = write("[\"" + SqlKey.of(tempDir, second) + "\"]");

    ScanReport filtered = SqlKeyAllowlist.filter(report, tempDir, allowlist);

    assertEquals(Collections.singletonList(second), filtered.sqlObjects());
    assertEquals("acme", filtered.project());
  }

  @Test
  void noAllowlistLeavesReportUnchanged() throws Exception {
    ScanReport report = new ScanReport("acme", Collections.singletonList(
        sql(tempDir.resolve("OrderMapper.xml"), "find", "SELECT 1")));

    assertSame(report, SqlKeyAllowlist.filter(report, tempDir, null));
  }

  @Test
  void rejectsEmptyAllowlist() throws Exception {
    ScanReport report = reportWithOneSql();
    Path allowlist = write("[]");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> SqlKeyAllowlist.filter(report, tempDir, allowlist));
    assertTrue(error.getMessage().contains("empty"));
  }

  @Test
  void rejectsDuplicateAllowlistKeys() throws Exception {
    ScanReport report = reportWithOneSql();
    String key = SqlKey.of(tempDir, report.sqlObjects().get(0));
    Path allowlist = write("[\"" + key + "\",\"" + key + "\"]");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> SqlKeyAllowlist.filter(report, tempDir, allowlist));
    assertTrue(error.getMessage().contains("duplicate"));
  }

  @Test
  void rejectsUnknownAndMalformedKeys() throws Exception {
    ScanReport report = reportWithOneSql();
    Path unknown = write("[\"v1:" + repeat('0', 64) + "\"]");
    IllegalArgumentException unknownError = assertThrows(IllegalArgumentException.class,
        () -> SqlKeyAllowlist.filter(report, tempDir, unknown));
    assertTrue(unknownError.getMessage().contains("unknown"));

    Path malformed = write("[\"not-a-sql-key\"]");
    IllegalArgumentException malformedError = assertThrows(IllegalArgumentException.class,
        () -> SqlKeyAllowlist.filter(report, tempDir, malformed));
    assertTrue(malformedError.getMessage().contains("invalid"));
  }

  @Test
  void rejectsNonArrayJsonSchemaAndNonStringEntries() throws Exception {
    ScanReport report = reportWithOneSql();
    Path object = write("{\"sqlKeys\":[]}");
    IllegalArgumentException objectError = assertThrows(IllegalArgumentException.class,
        () -> SqlKeyAllowlist.filter(report, tempDir, object));
    assertTrue(objectError.getMessage().contains("JSON array"));

    Path nonString = write("[123]");
    IllegalArgumentException entryError = assertThrows(IllegalArgumentException.class,
        () -> SqlKeyAllowlist.filter(report, tempDir, nonString));
    assertTrue(entryError.getMessage().contains("JSON strings"));
  }

  @Test
  void rejectsExtractedKeyCollisionBeforeFiltering() throws Exception {
    SqlObject sql = sql(tempDir.resolve("OrderMapper.xml"), "find", "SELECT 1");
    ScanReport report = new ScanReport("acme", Arrays.asList(sql, sql));
    Path allowlist = write("[\"" + SqlKey.of(tempDir, sql) + "\"]");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> SqlKeyAllowlist.filter(report, tempDir, allowlist));
    assertTrue(error.getMessage().contains("collision"));
  }

  private ScanReport reportWithOneSql() {
    return new ScanReport("acme", Collections.singletonList(
        sql(tempDir.resolve("OrderMapper.xml"), "find", "SELECT 1")));
  }

  private Path write(String contents) throws Exception {
    Path file = tempDir.resolve("allowlist-" + System.nanoTime() + ".txt");
    Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private static SqlObject sql(Path file, String statementId, String text) {
    return new SqlObject(new SqlOrigin(SourceKind.MYBATIS_XML, file, 10, 1,
        "com.acme.OrderMapper", statementId, "select", null, null), text, false);
  }

  private static SqlObject annotationSql(
      Path file, String className, String methodName, String text) {
    return new SqlObject(new SqlOrigin(SourceKind.MYBATIS_ANNOTATION, file, 10, 1,
        className, methodName, "select", className, methodName), text, false);
  }

  private static SqlObject jdbcSql(
      Path file, String className, String methodName, String text) {
    return new SqlObject(new SqlOrigin(SourceKind.JAVA_JDBC, file, 10, 1,
        null, null, null, className, methodName), text, false);
  }

  private static String repeat(char value, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, value);
    return new String(chars);
  }
}
