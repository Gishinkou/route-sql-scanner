package com.acme.routesql.report;

import com.acme.routesql.model.ScanReport;
import java.nio.charset.StandardCharsets;

public interface Reporter {
  String render(ScanReport report) throws Exception;

  default byte[] renderBytes(ScanReport report) throws Exception {
    return render(report).getBytes(StandardCharsets.UTF_8);
  }
}
