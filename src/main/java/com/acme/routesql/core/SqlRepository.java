package com.acme.routesql.core;

import com.acme.routesql.model.SqlObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SqlRepository {
  private final List<SqlObject> objects = new ArrayList<>();

  public void addAll(List<SqlObject> sqlObjects) {
    objects.addAll(sqlObjects);
  }

  public List<SqlObject> all() {
    return Collections.unmodifiableList(objects);
  }
}
