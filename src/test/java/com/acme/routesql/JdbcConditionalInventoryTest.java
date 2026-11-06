package com.acme.routesql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.report.CompactInventoryReporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcConditionalInventoryTest {
  @TempDir
  Path root;

  @Test
  void keepsTenantSwitchAlternativesAtOneSiteAndDoesNotReadFutureAssignments() throws Exception {
    List<JsonNode> sqls = scan(root, String.join("\n",
        "class Store {",
        "  void update(java.sql.Connection connection, boolean saveTenant) throws Exception {",
        "    String sql = \"UPDATE order_lock SET count=count+1 WHERE lock_key=?\";",
        "    if (saveTenant) {",
        "      sql = \"UPDATE order_lock SET count=count+1 WHERE lock_key=? AND tenant_id=?\";",
        "    }",
        "    try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {",
        "      statement.executeUpdate();",
        "    }",
        "    sql = \"DELETE FROM order_lock WHERE lock_key=?\";",
        "    connection.prepareStatement(sql);",
        "    connection.prepareStatement(sql);",
        "  }",
        "}"));

    assertEquals(4, sqls.size());
    JsonNode withTenant = sqls.get(0);
    JsonNode withoutTenant = sqls.get(1);
    assertEquals("UPDATE order_lock SET count=count+1 WHERE lock_key=? AND tenant_id=?", withTenant.path("sql").asText());
    assertEquals("UPDATE order_lock SET count=count+1 WHERE lock_key=?", withoutTenant.path("sql").asText());
    assertEquals("connection", jdbc(withTenant).path("receiver").asText());
    assertEquals("prepareStatement", jdbc(withTenant).path("method").asText());
    assertEquals("then", jdbc(withTenant).path("branches").get(0).path("arm").asText());
    assertEquals("else", jdbc(withoutTenant).path("branches").get(0).path("arm").asText());
    assertEquals(4, jdbc(withoutTenant).path("branches").get(0).path("line").asInt());
    assertEquals(jdbc(withTenant).path("id"), jdbc(withoutTenant).path("id"));
    assertEquals(withTenant.path("sourceStatementId"), withoutTenant.path("sourceStatementId"));
    assertNotEquals(withTenant.path("sqlKey"), withoutTenant.path("sqlKey"));
    assertEquals("DELETE FROM order_lock WHERE lock_key=?", sqls.get(2).path("sql").asText());
    assertEquals(sqls.get(2).path("sql"), sqls.get(3).path("sql"));
    assertNotEquals(sqls.get(2).path("sourceStatementId"), sqls.get(3).path("sourceStatementId"));
    assertNotEquals(sqls.get(2).path("sqlKey"), sqls.get(3).path("sqlKey"));
  }

  @Test
  void keepsAssignmentsFromOneBranchTogetherAndOmitsReceiverArgumentValues() throws Exception {
    String java = String.join("\n",
        "class Store {",
        "  void load(String tableName, boolean saveTenant) throws Exception {",
        "    String table = tableName;",
        "    String where = \" WHERE lock_key=?\";",
        "    if (saveTenant) {",
        "      table = \"tenant_lock\";",
        "      where += \" AND tenant_id=?\";",
        "    }",
        "    provider.connection(\"fixture-argument\").prepareStatement(\"SELECT message FROM \" + table + where);",
        "  }",
        "}");
    List<JsonNode> sqls = scan(root, java);
    List<JsonNode> relocated = scan(root.resolve("relocated"), "\n" + java);

    assertEquals(2, sqls.size());
    assertEquals("SELECT message FROM tenant_lock WHERE lock_key=? AND tenant_id=?", sqls.get(0).path("sql").asText());
    assertEquals("SELECT message FROM __DYNAMIC__ WHERE lock_key=?", sqls.get(1).path("sql").asText());
    assertEquals("provider.connection()", jdbc(sqls.get(0)).path("receiver").asText());
    assertFalse(jdbc(sqls.get(0)).toString().contains("fixture-argument"));
    assertEquals(sqls.get(0).path("sqlKey"), relocated.get(0).path("sqlKey"));
    assertEquals(jdbc(sqls.get(0)).path("id"), jdbc(relocated.get(0)).path("id"));
  }

  @Test
  void emitsConnectionSourceOnlyForExplicitAcquisitionAndInjection() throws Exception {
    List<JsonNode> sqls = scan(root, String.join("\n",
        "package example;",
        "import java.sql.Connection;",
        "import javax.sql.DataSource;",
        "import jakarta.annotation.Resource;",
        "class Store {",
        "  @Resource(name=\"primary\") private DataSource ds;",
        "  private DataSource archive;",
        "  public void setArchive(DataSource value) { this.archive = value; }",
        "  void query(Connection argument) throws Exception {",
        "    Connection conn = null;",
        "    conn = this.ds.getConnection();",
        "    conn.prepareStatement(\"SELECT 1\");",
        "    argument.prepareStatement(\"SELECT 2\");",
        "    Connection changed = this.ds.getConnection();",
        "    changed = argument;",
        "    changed.prepareStatement(\"SELECT 3\");",
        "    this.archive.getConnection().prepareCall(\"CALL proc()\");",
        "  }",
        "}"));

    assertEquals(4, sqls.size());
    JsonNode first = jdbc(sqls.get(0));
    assertEquals(64, first.path("sourceSha256").asText().length());
    assertEquals("example.Store", first.path("connectionSource").path("ownerClass").asText());
    assertEquals("ds", first.path("connectionSource").path("field").asText());
    assertEquals("primary", first.path("connectionSource").path("namedResource").asText());
    assertEquals(11, first.path("connectionSource").path("line").asInt());
    assertFalse(jdbc(sqls.get(1)).has("connectionSource"));
    assertFalse(jdbc(sqls.get(2)).has("connectionSource"));
    assertEquals("setArchive", jdbc(sqls.get(3)).path("connectionSource").path("setter").asText());
    assertEquals("archive", jdbc(sqls.get(3)).path("connectionSource").path("field").asText());
  }

  private static JsonNode jdbc(JsonNode row) {
    return row.path("origin").path("jdbcCallsite");
  }

  private static List<JsonNode> scan(Path checkout, String source) throws Exception {
    Files.createDirectories(checkout);
    Path java = checkout.resolve("Store.java");
    Files.write(java, source.getBytes(StandardCharsets.UTF_8));
    ScanReport report = new ScanEngine(new ScannerConfig()).scan(
        Collections.singletonList(java), Collections.emptyList(), Collections.emptyList());
    JsonNode sqls = new ObjectMapper().readTree(new CompactInventoryReporter(checkout).render(report)).path("sqls");
    return StreamSupport.stream(sqls.spliterator(), false).collect(Collectors.toList());
  }
}
