package com.acme.routesql.extract.mybatis;

import com.acme.routesql.extract.ExtractionContext;
import com.acme.routesql.extract.SqlExtractor;
import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.acme.routesql.util.SqlObjects;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class MyBatisXmlExtractor implements SqlExtractor {
  private static final List<String> STATEMENT_TAGS = List.of("select", "insert", "update", "delete");

  @Override
  public String name() {
    return "mybatis-xml";
  }

  @Override
  public boolean supports(Path path) {
    return path.getFileName().toString().endsWith(".xml");
  }

  @Override
  public List<SqlObject> extract(Path path, ExtractionContext context) throws Exception {
    String xml = Files.readString(path);
    if (!xml.contains("<mapper")) {
      return List.of();
    }

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setNamespaceAware(false);
    factory.setIgnoringComments(true);
    Document document = factory.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    Element mapper = document.getDocumentElement();
    if (!"mapper".equals(mapper.getTagName())) {
      return List.of();
    }

    String namespace = mapper.getAttribute("namespace");
    Map<String, Element> fragments = collectFragments(mapper, namespace);
    List<SqlObject> objects = new ArrayList<>();
    for (Element statement : MyBatisSqlScriptBuilder.childElements(mapper)) {
      String tagName = statement.getTagName();
      if (!STATEMENT_TAGS.contains(tagName) && !"sql".equals(tagName)) {
        continue;
      }
      String statementId = statement.getAttribute("id");
      List<SqlVariant> variants = sqlVariants(
          MyBatisSqlScriptBuilder.buildChildrenVariants(statement, fragments),
          context
      );
      LineColumn location = locate(xml, tagName, statementId);
      SqlOrigin origin = new SqlOrigin(
          SourceKind.MYBATIS_XML,
          path.toAbsolutePath().normalize(),
          location.line(),
          location.column(),
          namespace,
          statementId,
          "sql".equals(tagName) ? "FRAGMENT" : tagName.toUpperCase(Locale.ROOT),
          null,
          null
      );
      for (int i = 0; i < variants.size(); i++) {
        SqlVariant variant = variants.get(i);
        objects.add(SqlObjects.create(
            variant.raw(),
            variant.normalized(),
            origin,
            variant.dynamic(),
            variant.dynamic() ? List.of("mybatis", "dynamic") : List.of("mybatis"),
            attributes(i, variants.size())
        ));
      }
    }
    return objects;
  }

  private List<SqlVariant> sqlVariants(
      List<MyBatisSqlScriptBuilder.BuildResult> buildResults,
      ExtractionContext context
  ) {
    Map<String, SqlVariant> variants = new LinkedHashMap<>();
    for (MyBatisSqlScriptBuilder.BuildResult built : buildResults) {
      String raw = context.normalizer().normalizeMyBatisParameters(built.sql());
      boolean dynamic = built.dynamic() || raw.contains("__DYNAMIC__");
      String normalized = context.normalizer().normalize(raw);
      SqlVariant existing = variants.get(normalized);
      if (existing == null) {
        variants.put(normalized, new SqlVariant(raw, normalized, dynamic));
      } else if (!existing.dynamic() && dynamic) {
        variants.put(normalized, new SqlVariant(existing.raw(), existing.normalized(), true));
      }
    }
    return new ArrayList<>(variants.values());
  }

  private Map<String, Object> attributes(int variantIndex, int variantCount) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("extractor", name());
    if (variantCount > 1) {
      attributes.put("variantIndex", variantIndex + 1);
      attributes.put("variantCount", variantCount);
    }
    return attributes;
  }

  private Map<String, Element> collectFragments(Element mapper, String namespace) {
    Map<String, Element> fragments = new HashMap<>();
    for (Element child : MyBatisSqlScriptBuilder.childElements(mapper)) {
      if ("sql".equals(child.getTagName())) {
        String id = child.getAttribute("id");
        fragments.put(id, child);
        if (namespace != null && !namespace.isBlank()) {
          fragments.put(namespace + "." + id, child);
        }
      }
    }
    return fragments;
  }

  private LineColumn locate(String xml, String tagName, String id) {
    Pattern pattern = Pattern.compile("<" + tagName + "\\b[^>]*\\bid\\s*=\\s*['\"]" + Pattern.quote(id) + "['\"]");
    Matcher matcher = pattern.matcher(xml);
    if (!matcher.find()) {
      return new LineColumn(1, 1);
    }
    int line = 1;
    int column = 1;
    for (int i = 0; i < matcher.start(); i++) {
      if (xml.charAt(i) == '\n') {
        line++;
        column = 1;
      } else {
        column++;
      }
    }
    return new LineColumn(line, column);
  }

  private record LineColumn(int line, int column) {}

  private record SqlVariant(String raw, String normalized, boolean dynamic) {}
}
