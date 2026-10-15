package com.acme.routesql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.report.CompactInventoryReporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class IBatisCompatibilityTest {
  private ScanReport scan() throws Exception {
    Path dir = Paths.get("src/test/resources/fixtures/ibatis").toAbsolutePath();
    return new ScanEngine(new ScannerConfig()).scan(
        Collections.singletonList(dir), Collections.<String>emptyList(), Collections.<String>emptyList());
  }

  @Test
  void normalizesStatementsPlaceholdersDynamicsIterateAndIncludes() throws Exception {
    ScanReport report = scan();
    SqlObject find = statement(report, "find");
    String sql = find.rawSql();
    assertTrue(sql.contains("SELECT id, user_id, status FROM orders"), sql);
    assertTrue(sql.contains("user_id = ?"), sql);
    assertTrue(sql.contains("status = ?"), sql);
    assertTrue(sql.contains("id IN (?)"), sql);
    assertTrue(sql.contains("tenant = __DYNAMIC__"), sql);
    assertTrue(sql.contains("/*?if*/"), sql);
    assertTrue(find.dynamic());
    assertEquals(SourceKind.IBATIS_XML, find.origin().kind());
    assertEquals("legacy.orders", find.origin().namespace());
    assertEquals("SELECT", find.origin().statementType());

    assertTrue(statement(report, "create").rawSql().startsWith("INSERT INTO"));
    assertTrue(statement(report, "change").rawSql().contains("UPDATE orders SET"));
    assertTrue(statement(report, "remove").rawSql().startsWith("DELETE FROM"));
    assertEquals("SELECT", statement(report, "generic").origin().statementType());
  }

  @Test
  void removesFirstConditionalPrependInsideDynamicBlock() throws Exception {
    String sql = statement(scan(), "prependComma").rawSql();
    assertTrue(sql.contains("WHERE /*?if*/ id=?"), sql);
    assertFalse(sql.contains("WHERE /*?if*/ ,"), sql);
    assertFalse(sql.contains("WHERE /*?if*/ AND"), sql);
    assertTrue(sql.contains("/*?if*/ AND status=?"), sql);
  }

  @Test
  void preservesChildPrependWhenDynamicHasNoPrepend() throws Exception {
    ScanReport report = scan();
    String update = statement(report, "dynamicWithoutPrepend").rawSql();
    assertTrue(update.contains("SET modified=now() /*?if*/ , code=? /*?endif*/"), update);
    String empty = statement(report, "dynamicWithEmptyPrepend").rawSql();
    assertTrue(empty.contains("SET modified=now() /*?if*/ , name=? /*?endif*/"), empty);
    String select = statement(report, "dynamicAfterStaticWhere").rawSql();
    assertTrue(select.contains("WHERE 1=1 /*?if*/ AND id=? /*?endif*/"), select);
  }

  @Test
  void removesFirstChildPrependWhenDynamicSuppliesSet() throws Exception {
    String sql = statement(scan(), "dynamicWithSet").rawSql();
    assertTrue(sql.contains("SET /*?if*/ code=? /*?endif*/ /*?if*/ , name=?"), sql);
    assertFalse(sql.contains("SET /*?if*/ ,"), sql);
  }

  @Test
  void representsAdjacentComplementaryValuesWithExistingChooseProtocol() throws Exception {
    String sql = statement(scan(), "complementaryValues").rawSql();
    assertEquals("INSERT INTO pair_values(id, created, modified) VALUES (?, "
        + "/*?choose*/ /*?branch*/ now(), /*?endbranch*/ /*?or*/ "
        + "/*?branch*/ ?, /*?endbranch*/ /*?endchoose*/ now())", sql);
    assertFalse(sql.contains("/*?if*/"), sql);
  }

  @Test
  void preservesComplementaryBranchOrderAndIterateBindings() throws Exception {
    ScanReport report = scan();
    String reversed = statement(report, "complementaryValuesReversed").rawSql();
    assertTrue(reversed.contains("/*?choose*/ /*?branch*/ ?, /*?endbranch*/ /*?or*/ "
        + "/*?branch*/ now(), /*?endbranch*/ /*?endchoose*/"), reversed);
    String iterate = statement(report, "complementaryIterateValues").rawSql();
    assertTrue(iterate.contains("( ?, /*?choose*/ /*?branch*/ now(), /*?endbranch*/ /*?or*/ "
        + "/*?branch*/ ?, /*?endbranch*/ /*?endchoose*/ now() )"), iterate);
    assertFalse(iterate.contains("#"), iterate);
    assertFalse(iterate.contains("__ibatis_item"), iterate);
  }

  @Test
  void doesNotInferMutualExclusionWithoutAdjacentComplementaryConditions() throws Exception {
    ScanReport report = scan();
    for (String id : new String[] {"differentProperties", "sameNullCondition", "nonAdjacentPair"}) {
      String sql = statement(report, id).rawSql();
      assertFalse(sql.contains("/*?choose*/"), sql);
      assertTrue(sql.contains("/*?if*/"), sql);
    }
  }

  @Test
  void leavesCorrelatedColumnValueConditionsAndOpaqueDependenciesUnchanged() throws Exception {
    ScanReport report = scan();
    for (String id : new String[] {"correlatedColumnValues", "pairWithInclude", "pairWithOpaqueCondition"}) {
      String sql = statement(report, id).rawSql();
      assertFalse(sql.contains("/*?choose*/"), sql);
      assertTrue(sql.contains("/*?if*/"), sql);
    }
  }

  @Test
  void leavesComplexOrEmptyComplementaryBodiesUnchanged() throws Exception {
    ScanReport report = scan();
    for (String id : new String[] {"pairWithPrepend", "pairWithNestedBody", "pairWithEmptyBody"}) {
      String sql = statement(report, id).rawSql();
      assertFalse(sql.contains("/*?choose*/"), sql);
    }
  }

  @Test
  void excludesDocumentMetadataAndFailsClosedForUnknownStatementTags() throws Exception {
    ScanReport report = scan();
    for (SqlObject sql : report.sqlObjects()) {
      assertFalse(sql.rawSql().contains("MUST_NOT_BE_SQL"), sql.rawSql());
    }
    SqlObject unknown = statement(report, "unknown");
    assertTrue(unknown.rawSql().contains("__IBATIS_UNKNOWN_TAG_LEGACYCONDITION__"), unknown.rawSql());
    assertFalse(unknown.rawSql().contains("WHERE flag=1"), unknown.rawSql());
    assertTrue(unknown.dynamic());
  }

  @Test
  void protectsMissingAndCyclicIncludesAndSkipsProcedures() throws Exception {
    ScanReport report = scan();
    assertTrue(statement(report, "missing").rawSql().contains("__MISSING_INCLUDE__"));
    assertTrue(statement(report, "cyclic").rawSql().contains("__CYCLIC_INCLUDE__"));
    assertFalse(report.sqlObjects().stream().anyMatch(sql -> "callLegacy".equals(sql.origin().statementId())));
  }

  @Test
  void emitsIbatisProvenanceWhileKeepingProtocolSourceType() throws Exception {
    JsonNode root = new ObjectMapper().readTree(new CompactInventoryReporter().render(scan()));
    assertEquals(2, root.path("v").asInt());
    for (JsonNode sql : root.path("sqls")) {
      JsonNode origin = sql.path("origin");
      assertEquals("mybatis_xml", origin.path("sourceType").asText());
      assertEquals("ibatis", origin.path("framework").asText());
      assertEquals("2", origin.path("frameworkVersion").asText());
      assertEquals("1", origin.path("normalizerVersion").asText());
      assertTrue(sql.path("sourceStatementId").asText().matches("source-v1:[0-9a-f]{64}"));
      assertTrue(sql.hasNonNull("sqlKey"));
    }
  }

  private static SqlObject statement(ScanReport report, String id) {
    return report.sqlObjects().stream()
        .filter(sql -> id.equals(sql.origin().statementId()))
        .findFirst().orElseThrow(() -> new AssertionError("missing statement " + id));
  }
}
