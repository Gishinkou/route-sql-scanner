package com.acme.routesql.extract.mybatis;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class MyBatisSqlScriptBuilder {
  private MyBatisSqlScriptBuilder() {}

  static BuildResult buildChildren(Element element, Map<String, Element> fragments) {
    List<BuildResult> variants = buildChildrenVariants(element, fragments);
    return variants.isEmpty() ? new BuildResult("", false) : variants.get(0);
  }

  static List<BuildResult> buildChildrenVariants(Element element, Map<String, Element> fragments) {
    List<BuildResult> variants = List.of(new BuildResult("", false));
    NodeList nodes = element.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      variants = combine(variants, buildNodeVariants(nodes.item(i), fragments));
    }
    return dedupe(variants);
  }

  static BuildResult buildAnnotationScript(String sql) {
    List<BuildResult> variants = buildAnnotationScripts(sql);
    return variants.isEmpty() ? new BuildResult("", false) : variants.get(0);
  }

  static List<BuildResult> buildAnnotationScripts(String sql) {
    String trimmed = sql.trim();
    if (!looksLikeXmlScript(trimmed)) {
      return List.of(new BuildResult(sql, false));
    }
    try {
      String wrapped = trimmed.matches("(?is)^\\s*<script\\b.*")
          ? trimmed
          : "<script>" + trimmed + "</script>";
      Document document = newDocument(wrapped);
      Element script = document.getDocumentElement();
      return markDynamic(buildChildrenVariants(script, Map.of()));
    } catch (Exception ignored) {
      return List.of(new BuildResult(stripDynamicTags(sql), true));
    }
  }

  static List<Element> childElements(Element parent) {
    List<Element> elements = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        elements.add((Element) node);
      }
    }
    return elements;
  }

  private static List<BuildResult> buildNodeVariants(Node node, Map<String, Element> fragments) {
    if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
      return List.of(new BuildResult(node.getTextContent(), false));
    }
    if (node.getNodeType() != Node.ELEMENT_NODE) {
      return List.of(new BuildResult("", false));
    }

    Element element = (Element) node;
    String tag = element.getTagName();
    return switch (tag) {
      case "include" -> includeVariants(element, fragments);
      case "where" -> keywordBlockVariants("WHERE", buildChildrenVariants(element, fragments), true, true);
      case "set" -> keywordBlockVariants("SET", buildChildrenVariants(element, fragments), true, false);
      case "trim" -> trimVariants(element, fragments);
      case "foreach" -> List.of(new BuildResult("(__FOREACH__)", true));
      case "if" -> ifVariants(element, fragments);
      case "choose" -> chooseVariants(element, fragments);
      case "when", "otherwise", "script" -> markDynamic(buildChildrenVariants(element, fragments));
      case "bind" -> List.of(new BuildResult("", true));
      default -> {
        yield markDynamic(buildChildrenVariants(element, fragments));
      }
    };
  }

  private static List<BuildResult> includeVariants(Element element, Map<String, Element> fragments) {
    Element fragment = fragments.get(element.getAttribute("refid"));
    if (fragment == null) {
      return List.of(new BuildResult(" __MISSING_INCLUDE__ ", true));
    }
    return buildChildrenVariants(fragment, fragments);
  }

  private static List<BuildResult> ifVariants(Element element, Map<String, Element> fragments) {
    List<BuildResult> variants = new ArrayList<>();
    variants.add(new BuildResult("", true));
    variants.addAll(markDynamic(buildChildrenVariants(element, fragments)));
    return dedupe(variants);
  }

  private static List<BuildResult> chooseVariants(Element element, Map<String, Element> fragments) {
    List<BuildResult> variants = new ArrayList<>();
    boolean hasOtherwise = false;
    for (Element child : childElements(element)) {
      String tag = child.getTagName();
      if ("when".equals(tag) || "otherwise".equals(tag)) {
        hasOtherwise |= "otherwise".equals(tag);
        variants.addAll(markDynamic(buildChildrenVariants(child, fragments)));
      }
    }
    if (!hasOtherwise) {
      variants.add(new BuildResult("", true));
    }
    if (variants.isEmpty()) {
      return markDynamic(buildChildrenVariants(element, fragments));
    }
    return dedupe(variants);
  }

  private static List<BuildResult> trimVariants(Element element, Map<String, Element> fragments) {
    List<BuildResult> variants = new ArrayList<>();
    for (BuildResult children : buildChildrenVariants(element, fragments)) {
      variants.add(trimVariant(element, children));
    }
    return dedupe(variants);
  }

  private static BuildResult trimVariant(Element element, BuildResult children) {
    String body = children.sql();
    String prefixOverrides = element.getAttribute("prefixOverrides");
    if (!prefixOverrides.isBlank()) {
      for (String token : prefixOverrides.split("\\|")) {
        body = body.replaceFirst("(?i)^\\s*" + Pattern.quote(token.trim()) + "\\b", " ");
      }
    }
    String suffixOverrides = element.getAttribute("suffixOverrides");
    if (!suffixOverrides.isBlank() && suffixOverrides.contains(",")) {
      body = stripTrailingComma(body);
    }
    String prefix = element.getAttribute("prefix");
    String suffix = element.getAttribute("suffix");
    body = body.trim();
    if (body.isBlank()) {
      return new BuildResult("", true);
    }
    return new BuildResult((prefix + " " + body + " " + suffix).trim(), true);
  }

  private static List<BuildResult> keywordBlockVariants(
      String keyword,
      List<BuildResult> bodies,
      boolean dynamic,
      boolean stripLeadingBoolean
  ) {
    List<BuildResult> variants = new ArrayList<>();
    for (BuildResult body : bodies) {
      String sql = stripLeadingBoolean ? stripLeadingBoolean(body.sql()) : stripTrailingComma(body.sql());
      variants.add(keywordBlock(keyword, sql, dynamic || body.dynamic()));
    }
    return dedupe(variants);
  }

  private static BuildResult keywordBlock(String keyword, String body, boolean dynamic) {
    if (body == null || body.isBlank()) {
      return new BuildResult("", dynamic);
    }
    return new BuildResult(keyword + " " + body, dynamic);
  }

  private static List<BuildResult> combine(List<BuildResult> left, List<BuildResult> right) {
    List<BuildResult> combined = new ArrayList<>();
    for (BuildResult leftResult : left) {
      for (BuildResult rightResult : right) {
        combined.add(new BuildResult(
            appendSql(leftResult.sql(), rightResult.sql()),
            leftResult.dynamic() || rightResult.dynamic()
        ));
      }
    }
    return dedupe(combined);
  }

  private static String appendSql(String left, String right) {
    if (left == null || left.isBlank()) {
      return right == null ? "" : right;
    }
    if (right == null || right.isBlank()) {
      return left;
    }
    return left + " " + right;
  }

  private static List<BuildResult> markDynamic(List<BuildResult> variants) {
    return variants.stream()
        .map(variant -> new BuildResult(variant.sql(), true))
        .toList();
  }

  private static List<BuildResult> dedupe(List<BuildResult> variants) {
    Map<String, BuildResult> deduped = new LinkedHashMap<>();
    for (BuildResult variant : variants) {
      String key = normalizeSpace(variant.sql());
      BuildResult existing = deduped.get(key);
      if (existing == null) {
        deduped.put(key, variant);
      } else if (!existing.dynamic() && variant.dynamic()) {
        deduped.put(key, new BuildResult(existing.sql(), true));
      }
    }
    return new ArrayList<>(deduped.values());
  }

  private static String normalizeSpace(String sql) {
    return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
  }

  private static String stripLeadingBoolean(String sql) {
    return sql.replaceFirst("(?i)^\\s*(AND|OR)\\b", " ").trim();
  }

  private static String stripTrailingComma(String sql) {
    return sql.replaceFirst(",\\s*$", "").trim();
  }

  private static boolean looksLikeXmlScript(String sql) {
    return sql.matches("(?is).*<\\s*(script|where|set|trim|foreach|choose|when|otherwise|if|bind)\\b.*");
  }

  private static String stripDynamicTags(String sql) {
    return sql
        .replaceAll("(?is)</?script\\b[^>]*>", " ")
        .replaceAll("(?is)</?(if|choose|when|otherwise|bind)\\b[^>]*>", " ")
        .replaceAll("(?is)<where\\b[^>]*>", " WHERE ")
        .replaceAll("(?is)</where>", " ")
        .replaceAll("(?is)<set\\b[^>]*>", " SET ")
        .replaceAll("(?is)</set>", " ")
        .replaceAll("(?is)</?trim\\b[^>]*>", " ")
        .replaceAll("(?is)<foreach\\b[^>]*>.*?</foreach>", " (__FOREACH__) ");
  }

  private static Document newDocument(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setNamespaceAware(false);
    factory.setIgnoringComments(true);
    return factory.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  record BuildResult(String sql, boolean dynamic) {}
}
