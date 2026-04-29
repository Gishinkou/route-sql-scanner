package com.acme.routesql.report;

import com.acme.routesql.model.ScanReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class JsonReporter implements Reporter {
  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  @Override
  public String render(ScanReport report) throws Exception {
    return mapper.writeValueAsString(report) + System.lineSeparator();
  }
}
