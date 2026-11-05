package com.acme.routesql.extract;

import com.acme.routesql.normalize.SqlNormalizer;
import com.acme.routesql.extract.mybatis.MyBatisResourceContexts;
import com.acme.routesql.extract.mybatis.MyBatisTemplates;
import java.util.Collections;
import java.util.Map;
import org.w3c.dom.Element;

public final class ExtractionContext {
  private final SqlNormalizer normalizer;
  private final Map<String, Element> globalFragments;
  private final MyBatisResourceContexts resourceContexts;

  public ExtractionContext(SqlNormalizer normalizer) {
    this(normalizer, Collections.emptyMap());
  }

  public ExtractionContext(SqlNormalizer normalizer, Map<String, Element> globalFragments) {
    this(normalizer, globalFragments, null);
  }

  public ExtractionContext(SqlNormalizer normalizer, Map<String, Element> globalFragments,
      MyBatisResourceContexts resourceContexts) {
    this.normalizer = normalizer;
    this.globalFragments = globalFragments;
    this.resourceContexts = resourceContexts;
  }

  public SqlNormalizer normalizer() {
    return normalizer;
  }

  public Map<String, Element> globalFragments() {
    return globalFragments;
  }

  public Map<String, Object> captureTemplate(Element statement, Map<String, Element> fragments) {
    return resourceContexts == null ? MyBatisTemplates.capture(statement, fragments)
        : resourceContexts.capture(statement, fragments);
  }
}
