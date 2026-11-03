package com.acme.routesql;

import com.acme.routesql.cli.ScanCommand;
import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.extract.mybatis.MyBatisTemplateRenderer;
import com.acme.routesql.extract.mybatis.ParameterScenarios;
import com.acme.routesql.model.SqlObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class ParameterScenarioAdmissionTest {
  @TempDir Path root;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void explicitWitnessesPreserveDefinitionsAndWorkWithAllowlist() throws Exception {
    Path xml = write("Mapper.xml", "<mapper namespace='Orders'><select id='q'>select id from ${tableName} where id=#{id}</select>"
        + "<select id='other'>select 1</select></mapper>");
    Path baseline = root.resolve("baseline.json");
    assertEquals(0, scan(xml, baseline));
    JsonNode original = mapper.readTree(baseline.toFile()).path("sqls").get(0);
    Path allowlist = write("allowlist.json", mapper.writeValueAsString(Collections.singletonList(original.path("sqlKey").asText())));
    Path parameters = write("parameters.json", "{\"statements\":{\"Orders.q\":[{\"id\":\"source-7\","
        + "\"parameters\":{\"tableName\":\"orders_00\",\"id\":7}}]}}");
    Path output = root.resolve("witness.json");
    assertEquals(0, scan(xml, output, "--sql-key-allowlist", allowlist.toString(), "--parameter-scenarios", parameters.toString()));
    JsonNode inventory = mapper.readTree(output.toFile());
    assertEquals(1, inventory.path("sqls").size());
    JsonNode witnessed = inventory.path("sqls").get(0);
    for (String field : new String[]{"sqlKey", "sourceStatementId", "sql", "origin"}) {
      assertEquals(original.path(field), witnessed.path(field), field);
    }
    assertEquals(ParameterScenarios.CAPABILITY, inventory.path("requiredCapabilities").get(0).asText());
    JsonNode scenario = witnessed.path("renderedScenarios").get(0);
    assertEquals("RENDERED", scenario.path("status").asText());
    assertTrue(scenario.path("sql").asText().contains("orders_00"));
    assertEquals(7, scenario.path("parameters").get(0).asInt());
    assertEquals("java.lang.Integer", scenario.path("parameterTypes").get(0).asText());
    assertEquals("SAMPLED", witnessed.path("scenarioCoverage").asText());
  }

  @Test
  void unknownOrAmbiguousStatementCannotSilentlyDiscardOrReuseWitness() throws Exception {
    Path xml = write("one/Mapper.xml", "<mapper namespace='Orders'><select id='q'>select #{id}</select></mapper>");
    Path unknown = write("unknown.json", "{\"statements\":{\"Other.q\":[{\"id\":\"p1\",\"parameters\":{\"id\":7}}]}}");
    Path output = root.resolve("failed.json");
    assertNotEquals(0, scan(xml, output, "--parameter-scenarios", unknown.toString()));
    assertFalse(Files.exists(output));
    write("two/Mapper.xml", "<mapper namespace='Orders'><select id='q'>select #{id}</select></mapper>");
    Path parameters = write("parameters.json", "{\"statements\":{\"Orders.q\":[{\"id\":\"p1\",\"parameters\":{\"id\":7}}]}}");
    assertNotEquals(0, scan(root, output, "--parameter-scenarios", parameters.toString()));
    assertFalse(Files.exists(output));
  }

  @Test
  void malformedWitnessesAndDuplicateJsonKeysFailAdmission() throws Exception {
    for (String contents : new String[]{"{}", "{\"statements\":{}}", "{\"statements\":{\"Orders.q\":[]}}",
        "{\"statements\":{\"Orders.q\":[{\"id\":\"p1\"}]}}",
        "{\"statements\":{\"Orders.q\":[{\"id\":\"p1\",\"parameters\":{}},{\"id\":\"p1\",\"parameters\":{}}]}}",
        "{\"statements\":{\"Orders.q\":[{\"id\":\"p1\",\"parameters\":{\"id\":1,\"id\":2}}]}}"}) {
      Path input = write("invalid.json", contents);
      assertThrows(Exception.class, () -> ParameterScenarios.load(input));
    }
  }

  @Test
  void missingJdbcPropertyIsUnrenderableWhileExplicitNullRemainsNativeNull() throws Exception {
    Path xml = write("Mapper.xml", "<mapper namespace='Orders'><select id='q'>select #{id}</select></mapper>");
    SqlObject sql = new ScanEngine(new ScannerConfig()).scan(Collections.singletonList(xml), Collections.emptyList(),
        Collections.emptyList()).sqlObjects().get(0);
    Map<String, Object> missing = MyBatisTemplateRenderer.render(sql.template(), Collections.emptyMap(), Collections.emptyMap());
    assertEquals("UNRENDERABLE", missing.get("status"));
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("id", null);
    Map<String, Object> supplied = MyBatisTemplateRenderer.render(sql.template(), input, Collections.emptyMap());
    assertEquals("RENDERED", supplied.get("status"));
    assertEquals(Collections.singletonList(null), supplied.get("parameters"));
    assertEquals(Collections.singletonList("null"), supplied.get("parameterTypes"));
  }

  @Test
  void missingTextIdentifierCannotRenderAsAnEmptyString() throws Exception {
    Path xml = write("Mapper.xml", "<mapper namespace='Orders'><select id='q'>select id from ${tableName} where id=#{id}</select></mapper>");
    SqlObject sql = new ScanEngine(new ScannerConfig()).scan(Collections.singletonList(xml), Collections.emptyList(),
        Collections.emptyList()).sqlObjects().get(0);
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("id", 7);
    Map<String, Object> missing = MyBatisTemplateRenderer.render(sql.template(), input, Collections.emptyMap());
    assertEquals("UNRENDERABLE", missing.get("status"));
    assertFalse(missing.containsKey("sql"));
  }

  private int scan(Path path, Path output, String... extra) {
    java.util.List<String> args = new java.util.ArrayList<>();
    Collections.addAll(args, "--path", path.toString(), "--project-root", root.toString(), "--output", output.toString());
    Collections.addAll(args, extra);
    return new CommandLine(new ScanCommand()).execute(args.toArray(new String[0]));
  }

  private Path write(String name, String value) throws Exception {
    Path file = root.resolve(name);
    Files.createDirectories(file.getParent());
    Files.write(file, value.getBytes(StandardCharsets.UTF_8));
    return file;
  }
}
