package com.acme.routesql;

import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.extract.mybatis.MyBatisResourceContexts;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.report.CompactInventoryReporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MyBatisScopedSqlTest {
  @TempDir Path project;
  private final ObjectMapper mapper = new ObjectMapper();

  private Path write(String name, String value) throws Exception {
    Path path = project.resolve(name);
    Files.createDirectories(path.getParent());
    Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    return path;
  }

  private MyBatisResourceContexts contexts(String[]... groups) throws Exception {
    List<Map<String, Object>> values = new ArrayList<>();
    for (int i = 0; i < groups.length; i++) {
      Map<String, Object> context = new LinkedHashMap<>();
      context.put("contextId", "factory-" + i);
      context.put("resourcePaths", Arrays.asList(groups[i]));
      values.add(context);
    }
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("version", MyBatisResourceContexts.VERSION);
    input.put("contexts", values);
    Path path = write("contexts.json", mapper.writeValueAsString(input));
    return MyBatisResourceContexts.load(path, project);
  }

  private JsonNode scan(MyBatisResourceContexts contexts, Map<String, Object> scenarios) throws Exception {
    ScanReport report = new ScanEngine(new ScannerConfig(), contexts).scan(
        Collections.singletonList(project), Collections.emptyList(), Collections.emptyList());
    return mapper.readTree(new CompactInventoryReporter(project, scenarios, contexts != null).render(report));
  }

  private JsonNode statement(JsonNode inventory, String namespace, String id) {
    for (JsonNode row : inventory.get("sqls")) {
      if (namespace.equals(row.path("origin").path("namespace").asText())
          && id.equals(row.path("origin").path("statementId").asText())) return row;
    }
    throw new AssertionError("Missing " + namespace + "." + id);
  }

  @Test
  void unregisteredFragmentCannotBecomeExecutableWithoutParameterWitnesses() throws Exception {
    write("src/main/resources/a.xml", "<mapper namespace='a'><select id='find'>select <include refid='b.columns'/> from orders</select></mapper>");
    write("src/main/resources/b.xml", "<mapper namespace='b'><sql id='columns'>id</sql></mapper>");
    JsonNode legacy = scan(null, Collections.emptyMap());
    JsonNode scoped = scan(contexts(new String[]{"src/main/resources/a.xml"}), Collections.emptyMap());
    JsonNode oldRow = statement(legacy, "a", "find");
    JsonNode row = statement(scoped, "a", "find");
    assertEquals("select id from orders", oldRow.get("sql").asText());
    assertFalse(legacy.has("factoryScopedSql"));
    assertFalse(oldRow.has("renderedScenarios"));
    assertEquals("mybatis-resource-context/v1", scoped.get("factoryScopedSql").asText());
    assertEquals("native-parameter-witnesses/v1", scoped.get("requiredCapabilities").get(0).asText());
    assertEquals("__DYNAMIC__", row.get("sql").asText());
    assertTrue(row.get("dynamic").asBoolean());
    JsonNode gap = row.get("renderedScenarios").get(0);
    assertEquals("UNRENDERABLE", gap.get("status").asText());
    assertTrue(gap.get("error").asText().contains("Missing SQL fragment: b.columns"));
    assertFalse(gap.has("parameters"));
    assertFalse(gap.has("witness"));
    assertEquals(oldRow.get("sourceStatementId"), row.get("sourceStatementId"));
    assertNotEquals(oldRow.get("sqlKey"), row.get("sqlKey"));
  }

  @Test
  void separateFactoriesUseTheirOwnNativeExpandedScript() throws Exception {
    write("left/src/main/resources/shared.xml", "<mapper namespace='shared'><sql id='columns'>left_id</sql></mapper>");
    write("right/src/main/resources/shared.xml", "<mapper namespace='shared'><sql id='columns'>right_id</sql></mapper>");
    write("left/src/main/resources/a.xml", "<mapper namespace='a'><select id='find'>select <include refid='shared.columns'/> from orders</select></mapper>");
    write("right/src/main/resources/b.xml", "<mapper namespace='b'><select id='find'>select <include refid='shared.columns'/> from orders</select></mapper>");
    JsonNode scoped = scan(contexts(
        new String[]{"left/src/main/resources/a.xml", "left/src/main/resources/shared.xml"},
        new String[]{"right/src/main/resources/b.xml", "right/src/main/resources/shared.xml"}), Collections.emptyMap());
    assertEquals("select left_id from orders", statement(scoped, "a", "find").get("sql").asText());
    assertEquals("select right_id from orders", statement(scoped, "b", "find").get("sql").asText());
    assertFalse(statement(scoped, "a", "find").has("renderedScenarios"));
    assertFalse(statement(scoped, "b", "find").has("renderedScenarios"));
  }

  @Test
  void oneDefinitionWithConflictingFactoryTemplatesRemainsAnUnrenderableGap() throws Exception {
    write("src/main/resources/a.xml", "<mapper namespace='a'><select id='find'>select <include refid='shared.columns'/> from orders</select></mapper>");
    write("left.xml", "<mapper namespace='shared'><sql id='columns'>left_id</sql></mapper>");
    write("right.xml", "<mapper namespace='shared'><sql id='columns'>right_id</sql></mapper>");
    JsonNode scoped = scan(contexts(new String[]{"src/main/resources/a.xml", "left.xml"},
        new String[]{"src/main/resources/a.xml", "right.xml"}), Collections.emptyMap());
    JsonNode row = statement(scoped, "a", "find");
    assertEquals("__DYNAMIC__", row.get("sql").asText());
    assertEquals("UNRENDERABLE", row.get("renderedScenarios").get(0).get("status").asText());
    assertTrue(row.get("renderedScenarios").get(0).get("error").asText().contains("select a context explicitly"));
    assertEquals(2, row.get("template").get("templatesByContext").size());
  }

  @Test
  void selectKeyUsesTheSameFactoryScopeAndRemainsASeparateIdentity() throws Exception {
    write("src/main/resources/a.xml", "<mapper namespace='a'><insert id='add'>insert into orders(id) values(1)<selectKey keyProperty='id'>select <include refid='b.columns'/> from orders</selectKey></insert></mapper>");
    write("src/main/resources/b.xml", "<mapper namespace='b'><sql id='columns'>id</sql></mapper>");
    JsonNode blocked = scan(contexts(new String[]{"src/main/resources/a.xml"}), Collections.emptyMap());
    JsonNode blockedKey = statement(blocked, "a", "add!selectKey");
    assertEquals("__DYNAMIC__", blockedKey.get("sql").asText());
    assertEquals("UNRENDERABLE", blockedKey.get("renderedScenarios").get(0).get("status").asText());
    JsonNode resolved = scan(contexts(new String[]{"src/main/resources/a.xml", "src/main/resources/b.xml"}), Collections.emptyMap());
    assertEquals("insert into orders(id) values(1)", statement(resolved, "a", "add").get("sql").asText());
    assertEquals("select id from orders", statement(resolved, "a", "add!selectKey").get("sql").asText());
    assertEquals(blockedKey.get("sourceStatementId"), statement(resolved, "a", "add!selectKey").get("sourceStatementId"));
  }

  @Test
  void validScopedParameterWitnessesStillUseNativeMybatisRendering() throws Exception {
    write("src/main/resources/a.xml", "<mapper namespace='a'><select id='find'>select <include refid='b.columns'><property name='column' value='id'/></include> from orders where id=#{id}</select></mapper>");
    write("src/main/resources/b.xml", "<mapper namespace='b'><sql id='columns'>${column}</sql></mapper>");
    Map<String, Object> witness = new LinkedHashMap<>();
    witness.put("id", "id-7");
    witness.put("parameters", Collections.singletonMap("id", 7));
    Map<String, Object> scenarios = new LinkedHashMap<>();
    scenarios.put("statements", Collections.singletonMap("a.find", Collections.singletonList(witness)));
    JsonNode scoped = scan(contexts(new String[]{"src/main/resources/a.xml", "src/main/resources/b.xml"}), scenarios);
    JsonNode row = statement(scoped, "a", "find");
    assertEquals("select id from orders where id=?", row.get("sql").asText());
    JsonNode rendered = row.get("renderedScenarios").get(0);
    assertEquals("RENDERED", rendered.get("status").asText());
    assertEquals(7, rendered.get("parameters").get(0).asInt());
    assertEquals("id-7", rendered.get("id").asText());
  }

  @Test
  void unresolvedScopePreservesEveryExplicitWitnessAndItsIdentity() throws Exception {
    write("src/main/resources/a.xml", "<mapper namespace='a'><select id='find'>select <include refid='b.columns'/> from orders where id=#{id}</select></mapper>");
    write("src/main/resources/b.xml", "<mapper namespace='b'><sql id='columns'>id</sql></mapper>");
    List<Map<String, Object>> witnesses = new ArrayList<>();
    for (int value : new int[]{7, 19}) {
      Map<String, Object> witness = new LinkedHashMap<>();
      witness.put("id", "id-" + value);
      witness.put("parameters", Collections.singletonMap("id", value));
      witnesses.add(witness);
    }
    Map<String, Object> scenarios = new LinkedHashMap<>();
    scenarios.put("statements", Collections.singletonMap("a.find", witnesses));
    JsonNode scoped = scan(contexts(new String[]{"src/main/resources/a.xml"}), scenarios);
    JsonNode rendered = statement(scoped, "a", "find").get("renderedScenarios");
    assertEquals(2, rendered.size());
    for (int index = 0; index < witnesses.size(); index++) {
      JsonNode scenario = rendered.get(index);
      assertEquals(witnesses.get(index).get("id"), scenario.get("id").asText());
      assertEquals(mapper.valueToTree(witnesses.get(index).get("parameters")), scenario.get("witness"));
      assertEquals("UNRENDERABLE", scenario.get("status").asText());
      assertTrue(scenario.get("error").asText().contains("Missing SQL fragment: b.columns"));
      assertFalse(scenario.has("parameters"));
      assertFalse(scenario.has("sql"));
    }
  }
}
