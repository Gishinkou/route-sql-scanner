package com.acme.routesql.rule;

import com.acme.routesql.model.Diagnostic;
import com.acme.routesql.model.SqlObject;
import java.util.List;

public interface SqlRule {
  String id();

  List<Diagnostic> apply(List<SqlObject> sqlObjects);
}
