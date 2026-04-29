package com.acme.routesql.extract;

import com.acme.routesql.model.SqlObject;
import java.nio.file.Path;
import java.util.List;

public interface SqlExtractor {
  String name();

  boolean supports(Path path);

  List<SqlObject> extract(Path path, ExtractionContext context) throws Exception;
}
