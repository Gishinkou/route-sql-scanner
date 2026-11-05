package com.acme.routesql;

import com.acme.routesql.cli.ScanCommand;
import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.extract.mybatis.MyBatisTemplateRenderer;
import com.acme.routesql.model.SqlObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureCoverageRepairTest {
  @TempDir Path root;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void explicitFactoryResourcesRestoreIncludesWithoutChangingOrDroppingDefinitions() throws Exception {
    write("app-a/Mapper.xml", mapper("a", "id, name"));
    write("app-b/Mapper.xml", mapper("b", "id, state"));
    JsonNode before = scan("before.json");
    Path scopes = write("contexts.json", "{\"version\":\"mybatis-resource-context/v1\",\"contexts\":["
        + "{\"contextId\":\"factory-a\",\"resourcePaths\":[\"app-a/Mapper.xml\"]},"
        + "{\"contextId\":\"factory-b\",\"resourcePaths\":[\"app-b/Mapper.xml\"]}]}");
    JsonNode after = scan("after.json", "--mapper-resource-context", scopes.toString());
    assertEquals(2, after.path("sqls").size());
    for (int i = 0; i < 2; i++) {
      JsonNode old = before.path("sqls").get(i), row = after.path("sqls").get(i);
      assertEquals("UNRESOLVED", old.path("template").path("status").asText());
      assertEquals(old.path("sqlKey"), row.path("sqlKey"));
      assertEquals(old.path("sourceStatementId"), row.path("sourceStatementId"));
      assertEquals("READY", row.path("template").path("status").asText());
      assertEquals(1, row.path("template").path("resourceContextIds").size());
    }
    assertTrue(after.path("sqls").get(0).path("template").path("script").asText().contains("id, name"));
    assertTrue(after.path("sqls").get(1).path("template").path("script").asText().contains("id, state"));
  }

  @Test
  void genuinelyConflictingFactoryResourcesRemainUnresolved() throws Exception {
    write("app-a/Mapper.xml", mapper("a", "id, name"));
    write("app-b/Mapper.xml", mapper("b", "id, state"));
    Path scopes = write("contexts.json", "{\"version\":\"mybatis-resource-context/v1\",\"contexts\":["
        + "{\"contextId\":\"factory\",\"resourcePaths\":[\"app-a/Mapper.xml\",\"app-b/Mapper.xml\"]}]}");
    JsonNode rows = scan("out.json", "--mapper-resource-context", scopes.toString()).path("sqls");
    assertEquals("UNRESOLVED", rows.get(0).path("template").path("status").asText());
    assertTrue(rows.get(0).path("template").path("error").asText().contains("Ambiguous SQL fragment"));
  }

  @Test
  void aSharedStatementRetainsDifferentFactoryTemplatesInsteadOfPickingOne() throws Exception {
    write("Shared.xml", "<mapper namespace='Statements'><select id='q'>select <include refid='Cols.cols'/> from orders</select></mapper>");
    write("app-a/Cols.xml", "<mapper namespace='Cols'><sql id='cols'>id,name</sql></mapper>");
    write("app-b/Cols.xml", "<mapper namespace='Cols'><sql id='cols'>id,state</sql></mapper>");
    Path scopes = write("contexts.json", "{\"version\":\"mybatis-resource-context/v1\",\"contexts\":["
        + "{\"contextId\":\"a\",\"resourcePaths\":[\"Shared.xml\",\"app-a/Cols.xml\"]},"
        + "{\"contextId\":\"b\",\"resourcePaths\":[\"Shared.xml\",\"app-b/Cols.xml\"]}]}");
    JsonNode template = scan("out.json", "--mapper-resource-context", scopes.toString()).path("sqls").get(0).path("template");
    assertEquals("UNRESOLVED", template.path("status").asText());
    assertEquals(2, template.path("templatesByContext").size());
    assertFalse(template.has("script"));
  }

  @Test
  void annotationsPreserveNativeScriptsAndResolveLocalStringConstants() throws Exception {
    write("Mapper.java", "package demo; import org.apache.ibatis.annotations.*; public interface Mapper {"
        + "String PREFIX=\"select * from orders\"; String QUERY=PREFIX+\" where id=#{id}\";"
        + "@Select(QUERY) Object constant(); @Select(Mapper.QUERY) Object qualified();"
        + "@Select(\"<script>select * from orders <where><if test='id != null'>id=#{id}</if></where></script>\") Object dynamic();"
        + "@Select(\"select * from orders where id &lt; #{id}\") Object plain();"
        + "@Select(\"  <script>select * from orders where id=#{id}</script>  \") Object paddedScript(); }");
    List<SqlObject> sqls = objects();
    assertEquals(5, sqls.size());
    for (SqlObject sql : sqls) {
      Map<String, Object> rendered = render(sql, params("id", 7));
      assertEquals("RENDERED", rendered.get("status"), rendered.toString());
      assertEquals(Collections.singletonList(7), rendered.get("parameters"));
    }
    assertTrue(render(sqls.get(3), params("id", 7)).get("sql").toString().contains("&lt;"),
        "Plain annotation SQL must not be interpreted as XML");
    assertFalse(render(sqls.get(4), params("id", 7)).get("sql").toString().contains("<script>"));
  }

  @Test
  void unresolvedAnnotationConstantsAndProvidersRetainStatementIdentities() throws Exception {
    write("Mapper.java", "package demo; import org.apache.ibatis.annotations.*; public interface Mapper {"
        + "String A=B; String B=A; @Select(A) Object cyclic();"
        + "@Select(External.SQL) Object external(); @SelectProvider(type=Provider.class,method=\"sql\") Object provider(); }");
    JsonNode rows = scan("out.json").path("sqls");
    assertEquals(3, rows.size());
    for (JsonNode row : rows) {
      assertTrue(row.path("sourceStatementId").asText().startsWith("source-v1:"));
      assertEquals("ANNOTATION_SQL_UNRESOLVED", row.path("template").path("errorCode").asText());
      assertEquals("UNRESOLVED", row.path("template").path("status").asText());
      assertTrue(row.path("dynamic").asBoolean());
    }
  }

  @Test
  void stringComparisonsUseExplicitValuesAndPreserveNativeBranchSelection() throws Exception {
    write("Mapper.xml", "<mapper namespace='Demo'><select id='q'>select * from orders <where>"
        + "<if test='kind != null and kind.equals(&quot;BUY&quot;)'>id=#{id}</if>"
        + "<if test='&quot;sell&quot;.equalsIgnoreCase(kind)'>id=#{other}</if></where></select></mapper>");
    SqlObject sql = objects().get(0);
    Map<String,Object> buy = render(sql, params("kind", "BUY", "id", 7));
    Map<String,Object> sell = render(sql, params("kind", "SELL", "other", 9));
    Map<String,Object> none = render(sql, params("kind", null));
    assertEquals("RENDERED", buy.get("status"), buy.toString());
    assertEquals(Collections.singletonList(7), buy.get("parameters"));
    assertEquals("RENDERED", sell.get("status"), sell.toString());
    assertEquals(Collections.singletonList(9), sell.get("parameters"));
    assertEquals("RENDERED", none.get("status"), none.toString());
    assertEquals(Collections.emptyList(), none.get("parameters"));
  }

  @Test
  void stringComparisonAdmissionDoesNotAllowBusinessCodeOrUnknownReceivers() throws Exception {
    for (String expression : Arrays.asList("@java.lang.System@getProperty(&quot;user.home&quot;).equals(&quot;x&quot;)",
        "kind.toUpperCase().equals(&quot;BUY&quot;)", "unknown.equals(&quot;BUY&quot;)", "kind.getClass().getName().equals(&quot;x&quot;)")) {
      write("Mapper.xml", "<mapper namespace='Demo'><select id='q'>select * from orders <if test='"
          + expression + "'>where id=1</if></select></mapper>");
      assertEquals("UNRENDERABLE", render(objects().get(0), params("kind", "BUY")).get("status"));
    }
  }

  @Test
  void literalStringSplitPreservesNativeBindAndForeachValues() throws Exception {
    for (String expression : Arrays.asList("value.split(',')", "value.split(&quot;,&quot;)",
        "&quot;1,2&quot;.split(',')", "nested.value.split(',')")) {
      write("Mapper.xml", splitMapper(expression));
      Map<String,Object> result = render(objects().get(0), params("value", "1,2",
          "nested", params("value", "1,2")));
      assertEquals("RENDERED", result.get("status"), result.toString());
      assertEquals(Arrays.asList("1", "2"), result.get("parameters"));
      assertEquals(Arrays.asList("java.lang.String", "java.lang.String"), result.get("parameterTypes"));
    }
  }

  @Test
  void splitOnExplicitNullKeepsNativeGuardAndNullFailureSemantics() throws Exception {
    write("Mapper.xml", "<mapper namespace='Demo'><select id='q'>select * from orders "
        + "<if test='enabled and value != null'>"
        + "<bind name='parts' value=\"value.split(',')\"/>"
        + "where id in <foreach collection='parts' item='part' open='(' close=')' separator=','>#{part}</foreach>"
        + "</if></select></mapper>");
    Map<String,Object> skipped = render(objects().get(0), params("enabled", false, "value", null));
    assertEquals("RENDERED", skipped.get("status"), skipped.toString());
    assertEquals(Collections.emptyList(), skipped.get("parameters"));
    write("Mapper.xml", splitMapper("value.split(',')"));
    Map<String,Object> reached = render(objects().get(0), params("value", null));
    assertEquals("UNRENDERABLE", reached.get("status"));
    assertFalse(reached.get("error").toString().contains("Unverified instance method"));
  }

  @Test
  void splitRejectsRegexDynamicDelimitersUnknownReceiversAndBusinessMethods() throws Exception {
    for (String expression : Arrays.asList("value.split('.')", "value.split('|')", "value.split('[,]')",
        "value.split(',,')", "value.split('')", "value.split(delimiter)", "value.split(',',2)",
        "unknown.split(',')", "number.split(',')", "items.split(',')", "nested.split(',')",
        "value.toUpperCase().split(',')", "value.replace('1','2')")) {
      write("Mapper.xml", splitMapper(expression));
      Map<String,Object> result = render(objects().get(0), params("value", "1,2", "delimiter", ",",
          "number", 1, "items", Arrays.asList("1", "2"), "nested", params("value", "1,2")));
      assertEquals("UNRENDERABLE", result.get("status"), expression);
      assertTrue(result.get("error").toString().contains("Unverified instance method"), result.toString());
    }
    String key = "route.sql.scanner.unreachable.split.sideEffect";
    assertNull(System.getProperty(key));
    write("Mapper.xml", "<mapper namespace='Demo'><select id='q'>select 1 <if test='false'>"
        + "<bind name='parts' value=\"@java.lang.System@setProperty('" + key + "','changed').split(',')\"/>"
        + "</if></select></mapper>");
    assertEquals("UNRENDERABLE", render(objects().get(0), params()).get("status"));
    assertNull(System.getProperty(key), "Unselected branches must not execute arbitrary business/static methods");
  }

  private String splitMapper(String expression) {
    return "<mapper namespace='Demo'><select id='q'>select * from orders "
        + "<bind name='parts' value=\"" + expression + "\"/>where id in "
        + "<foreach collection='parts' item='part' open='(' close=')' separator=','>#{part}</foreach>"
        + "</select></mapper>";
  }

  private String mapper(String id, String columns) {
    return "<mapper namespace='Shared'><sql id='cols'>" + columns + "</sql><select id='" + id
        + "'>select <include refid='cols'/> from orders where id=#{id}</select></mapper>";
  }

  private JsonNode scan(String output, String... options) throws Exception {
    java.util.List<String> args = new java.util.ArrayList<>(Arrays.asList("--path", root.toString(),
        "--project-root", root.toString(), "--output", root.resolve(output).toString()));
    args.addAll(Arrays.asList(options));
    assertEquals(0, new CommandLine(new ScanCommand()).execute(args.toArray(new String[0])));
    return json.readTree(root.resolve(output).toFile());
  }

  private List<SqlObject> objects() throws Exception {
    return new ScanEngine(new ScannerConfig()).scan(Collections.singletonList(root), Collections.emptyList(),
        Collections.emptyList()).sqlObjects();
  }

  private Map<String,Object> render(SqlObject sql, Map<String,Object> witness) {
    return MyBatisTemplateRenderer.render(sql.template(), witness, Collections.emptyMap());
  }

  private Path write(String name, String text) throws Exception {
    Path file = root.resolve(name);
    Files.createDirectories(file.getParent());
    Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private Map<String,Object> params(Object... pairs) {
    Map<String,Object> values = new LinkedHashMap<>();
    for (int i=0; i<pairs.length; i+=2) values.put((String) pairs[i], pairs[i+1]);
    return values;
  }
}
