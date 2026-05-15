package com.acme.routesql.report;

import com.acme.routesql.model.ScanReport;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ExcelReporter implements Reporter {
  private static final List<String> HEADERS = List.of(
      "identity.sourceKey",
      "identity.logicalName",
      "normalizedSql",
      "severity",
      "message",
      "tableName",
      "expectedRouteFields",
      "columns"
  );

  @Override
  public String render(ScanReport report) throws Exception {
    throw new UnsupportedOperationException("excel output is binary");
  }

  @Override
  public byte[] renderBytes(ScanReport report) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      entry(zip, "[Content_Types].xml", contentTypes());
      entry(zip, "_rels/.rels", rootRelationships());
      entry(zip, "xl/workbook.xml", workbook());
      entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
      entry(zip, "xl/styles.xml", styles());
      entry(zip, "xl/worksheets/sheet1.xml", worksheet(CompactDiagnosticRows.from(report)));
    }
    return bytes.toByteArray();
  }

  private String worksheet(List<CompactDiagnosticRow> rows) {
    StringBuilder builder = new StringBuilder();
    builder.append("""
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <sheetViews><sheetView workbookViewId="0"/></sheetViews>
        <sheetFormatPr defaultRowHeight="15"/>
        <cols>
        <col min="1" max="2" width="38" customWidth="1"/>
        <col min="3" max="3" width="80" customWidth="1"/>
        <col min="4" max="6" width="22" customWidth="1"/>
        <col min="7" max="8" width="30" customWidth="1"/>
        </cols>
        <sheetData>
        """);
    appendRow(builder, 1, HEADERS, true);
    int rowIndex = 2;
    for (CompactDiagnosticRow row : rows) {
      appendRow(builder, rowIndex++, List.of(
          value(row.sourceKey()),
          value(row.logicalName()),
          value(row.normalizedSql()),
          value(row.severity()),
          value(row.message()),
          value(row.tableName()),
          joined(row.expectedRouteFields()),
          joined(row.columns())
      ), false);
    }
    builder.append("""
        </sheetData>
        <autoFilter ref="A1:H1"/>
        <pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>
        </worksheet>
        """);
    return builder.toString();
  }

  private void appendRow(StringBuilder builder, int rowIndex, List<String> values, boolean header) {
    builder.append("<row r=\"").append(rowIndex).append("\">");
    for (int column = 0; column < values.size(); column++) {
      builder.append("<c r=\"")
          .append(cellRef(column, rowIndex))
          .append("\" t=\"inlineStr\"");
      if (header) {
        builder.append(" s=\"1\"");
      }
      builder.append("><is><t>")
          .append(escape(values.get(column)))
          .append("</t></is></c>");
    }
    builder.append("</row>");
  }

  private String cellRef(int zeroBasedColumn, int rowIndex) {
    StringBuilder column = new StringBuilder();
    int value = zeroBasedColumn + 1;
    while (value > 0) {
      int remainder = (value - 1) % 26;
      column.insert(0, (char) ('A' + remainder));
      value = (value - 1) / 26;
    }
    return column + Integer.toString(rowIndex);
  }

  private String value(String value) {
    return value == null ? "" : value;
  }

  private String joined(List<String> values) {
    return values == null ? "" : String.join(", ", values);
  }

  private String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private void entry(ZipOutputStream zip, String name, String content) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private String contentTypes() {
    return """
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
        </Types>
        """;
  }

  private String rootRelationships() {
    return """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
        """;
  }

  private String workbook() {
    return """
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <sheets><sheet name="diagnostics" sheetId="1" r:id="rId1"/></sheets>
        </workbook>
        """;
  }

  private String workbookRelationships() {
    return """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
        """;
  }

  private String styles() {
    return """
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
        <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
        <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
        <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" applyFont="1"/></cellXfs>
        </styleSheet>
        """;
  }
}
