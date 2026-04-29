package com.acme.routesql.report;

import com.acme.routesql.model.ScanReport;

public interface Reporter {
  String render(ScanReport report) throws Exception;
}
