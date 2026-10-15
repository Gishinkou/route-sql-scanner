package com.acme.routesql.extract;

import com.acme.routesql.normalize.SqlNormalizer;
import java.util.Collections;
import java.util.Map;
import org.w3c.dom.Element;

public final class ExtractionContext {
  private final SqlNormalizer normalizer;
  private final Map<String, Element> globalFragments;

  public ExtractionContext(SqlNormalizer normalizer) {
    this(normalizer, Collections.emptyMap());
  }

  public ExtractionContext(SqlNormalizer normalizer, Map<String, Element> globalFragments) {
    this.normalizer = normalizer;
    this.globalFragments = globalFragments;
  }

  public SqlNormalizer normalizer() {
    return normalizer;
  }

  public Map<String, Element> globalFragments() {
    return globalFragments;
  }
}
