package com.acme.routesql;

import com.acme.routesql.cli.ScanCommand;
import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.extract.mybatis.MyBatisTemplateRenderer;
import com.acme.routesql.model.SqlObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MyBatisParameterScenarioTest {
  @TempDir Path root;

  @Test
  void nativeConditionsKeepSameParameterBranchesCorrelated() throws Exception {
    SqlObject sql = extract("<mapper namespace='Orders'><select id='q'>select o.id from orders o "
        + "<if test='flag==1'>join extra e on e.id=o.id</if> where o.id=#{id} "
        + "<if test='flag==1'>and e.x=#{x}</if><if test='flag==2'>and o.state=#{state}</if>"
        + "</select></mapper>");
    Map<String, Object> one = render(sql, params("flag", 1, "id", 7, "x", 9));
    Map<String, Object> two = render(sql, params("flag", 2, "id", 8, "state", 3));
    assertEquals("RENDERED", one.get("status"));
    assertEquals(Arrays.asList(7, 9), one.get("parameters"));
    assertTrue(one.get("sql").toString().contains("join extra e"));
    assertFalse(one.get("sql").toString().contains("o.state"));
    assertEquals(Arrays.asList(8, 3), two.get("parameters"));
    assertFalse(two.get("sql").toString().contains("join extra e"));
    assertFalse(two.get("sql").toString().contains("e.x"));
    assertTrue(sql.template().get("script").toString().contains("flag==1"));
  }

  @Test
  void includesHonorNamespacesPropertiesAndEmptyOtherwise() throws Exception {
    write("Shared.xml", "<mapper namespace='Shared'><sql id='filter'>"
        + "<if test='enabled != null and enabled'>AND ${alias}.id=#{id}</if></sql></mapper>");
    SqlObject sql = extract("<mapper namespace='Orders'><sql id='filter'>AND wrong=1</sql>"
        + "<select id='q'>select o.id from orders o <where>"
        + "<include refid='Shared.filter'><property name='alias' value='o'/></include>"
        + "<choose><when test='mode==1'>AND first=1</when><otherwise/></choose>"
        + "</where></select></mapper>");
    Map<String, Object> rendered = render(sql, params("enabled", true, "id", 12, "mode", 2));
    assertEquals("RENDERED", rendered.get("status"), rendered.toString());
    assertEquals(Collections.singletonList(12), rendered.get("parameters"));
    assertTrue(rendered.get("sql").toString().contains("o.id=?"));
    assertFalse(rendered.get("sql").toString().contains("wrong"));
    assertFalse(rendered.get("sql").toString().contains("first"));
    assertFalse(sql.template().get("script").toString().contains("<include"));
    assertFalse(((java.util.List<?>) sql.template().get("includes")).isEmpty());
  }

  @Test
  void staticPlaceholdersAlsoAcceptActualParameters() throws Exception {
    SqlObject sql = extract("<mapper namespace='Orders'><select id='q'>select id from orders where id=#{id} limit #{limit}</select></mapper>");
    assertFalse(sql.dynamic());
    Map<String,Object> rendered = render(sql, params("id", 7, "limit", 10));
    assertEquals("RENDERED", rendered.get("status"));
    assertEquals(Arrays.asList(7, 10), rendered.get("parameters"));
  }

  @Test
  void foreachRetainsActualCardinalityAndJdbcBindingOrder() throws Exception {
    SqlObject sql = extract("<mapper namespace='Orders'><select id='q'>select id from orders where id in "
        + "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach>"
        + " and state=#{state}</select></mapper>");
    Map<String, Object> rendered = render(sql, params("ids", Arrays.asList(3, 5), "state", 9));
    assertEquals("RENDERED", rendered.get("status"), rendered.toString());
    assertEquals(Arrays.asList(3, 5, 9), rendered.get("parameters"));
  }

  @Test
  void unresolvedAndCyclicIncludesNeverBecomeConcreteSql() throws Exception {
    for (String includes : Arrays.asList("<include refid='missing'/>", "<include refid='loop'/>")) {
      SqlObject sql = extract("<mapper namespace='Orders'><sql id='loop'><include refid='loop'/></sql>"
          + "<select id='q'>select id from orders " + includes + "</select></mapper>");
      assertEquals("UNRESOLVED", sql.template().get("status"));
      assertEquals("UNRENDERABLE", render(sql, Collections.emptyMap()).get("status"));
    }
  }

  @Test
  void ambiguousReferencedIncludesFailButUnrelatedDefinitionsDoNotBlockRendering() throws Exception {
    write("One.xml", "<mapper namespace='Shared'><sql id='filter'>id=1</sql></mapper>");
    write("Two.xml", "<mapper namespace='Shared'><sql id='filter'>id=2</sql></mapper>");
    SqlObject referenced = extract("<mapper namespace='Orders'><select id='q'>select id from orders "
        + "<if test='enabled'><include refid='Shared.filter'/></if></select></mapper>");
    assertEquals("UNRESOLVED", referenced.template().get("status"));
    assertTrue(referenced.template().get("error").toString().contains("Ambiguous SQL fragment"));
    SqlObject unrelated = extract("<mapper namespace='Orders'><select id='q'>select id from orders "
        + "<if test='enabled'>where id=#{id}</if></select></mapper>");
    assertEquals("RENDERED", render(unrelated, params("enabled", true, "id", 1)).get("status"));
  }

  @Test
  void unverifiedStaticMethodsAreNeverExecuted() throws Exception {
    SqlObject sql = extract("<mapper namespace='Orders'><select id='q'>select id from orders "
        + "<if test='@java.lang.System@setProperty(&quot;routesql.test.sideeffect&quot;,&quot;bad&quot;) != null'>"
        + "where id=1</if></select></mapper>");
    assertEquals("UNRENDERABLE", render(sql, Collections.emptyMap()).get("status"));
    assertNull(System.getProperty("routesql.test.sideeffect"));
  }

  @Test
  void cliEmitsWitnessesWithoutChangingSourceStatementIdentity() throws Exception {
    Path mapper = write("Mapper.xml", "<mapper namespace='Orders'><select id='q'>select id from orders "
        + "<where><if test='id != null'>and id=#{id}</if></where></select></mapper>");
    Path scenarios = write("parameters.json", "{\"statements\":{\"Orders.q\":[{\"id\":\"id-7\",\"parameters\":{\"id\":7}}]}}");
    Path output = root.resolve("out.json");
    int exit = new CommandLine(new ScanCommand()).execute("--path", mapper.toString(),
        "--parameter-scenarios", scenarios.toString(), "--output", output.toString());
    assertEquals(0, exit);
    JsonNode row = new ObjectMapper().readTree(output.toFile()).path("sqls").get(0);
    assertEquals(7, row.path("renderedScenarios").get(0).path("parameters").get(0).asInt());
    assertEquals("SAMPLED", row.path("scenarioCoverage").asText());
    assertTrue(row.path("sourceStatementId").asText().startsWith("source-v1:"));
    assertTrue(row.path("sql").asText().contains("/*?if*/"));
  }

  private Map<String, Object> render(SqlObject sql, Map<String, Object> witness) {
    return MyBatisTemplateRenderer.render(sql.template(), witness, Collections.emptyMap());
  }

  private SqlObject extract(String xml) throws Exception {
    write("Mapper.xml", xml);
    return new ScanEngine(new ScannerConfig()).scan(Collections.singletonList(root), Collections.emptyList(),
        Collections.emptyList()).sqlObjects().stream().filter(s -> "q".equals(s.origin().statementId())).findFirst().get();
  }

  private Path write(String name, String contents) throws Exception {
    Path path = root.resolve(name);
    Files.write(path, contents.getBytes(StandardCharsets.UTF_8));
    return path;
  }

  private Map<String, Object> params(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }
}
