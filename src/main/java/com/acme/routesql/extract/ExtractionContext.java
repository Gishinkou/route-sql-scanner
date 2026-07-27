package com.acme.routesql.extract;

import com.acme.routesql.normalize.SqlNormalizer;

public final class ExtractionContext {
  private final SqlNormalizer normalizer;

  public ExtractionContext(SqlNormalizer normalizer) {
    this.normalizer = normalizer;
  }

  public SqlNormalizer normalizer() {
    return normalizer;
  }
}
