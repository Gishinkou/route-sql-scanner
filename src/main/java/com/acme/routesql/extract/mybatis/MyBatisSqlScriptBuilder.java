package com.acme.routesql.extract.mybatis;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    StringBuilder sql = new StringBuilder();
    boolean dynamic = false;
    NodeList nodes = element.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      BuildResult part = buildNode(nodes.item(i), fragments);
      appendSql(sql, part.sql());
      dynamic |= part.dynamic();
    }
    return new BuildResult(normalizeSpace(sql.toString()), dynamic);
  }

  static BuildResult buildAnnotationScript(String sql) {
    String trimmed = sql.trim();
    if (!looksLikeXmlScript(trimmed)) {
      return new BuildResult(sql, false);
    }
    try {
      String wrapped = trimmed.matches("(?is)^\\s*<script\\b.*")
          ? trimmed
          : "<script>" + trimmed + "</script>";
      Document document = newDocument(wrapped);
      Element script = document.getDocumentElement();
      BuildResult built = buildChildren(script, Map.of());
      return new BuildResult(built.sql(), true);
    } catch (Exception ignored) {
      return new BuildResult(normalizeSpace(stripDynamicTags(sql)), true);
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

  private static BuildResult buildNode(Node node, Map<String, Element> fragments) {
    if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
      return new BuildResult(node.getTextContent(), false);
    }
    if (node.getNodeType() != Node.ELEMENT_NODE) {
      return new BuildResult("", false);
    }

    Element element = (Element) node;
    String tag = element.getTagName();
    return switch (tag) {
      case "include" -> includeBuild(element, fragments);
      case "where" -> keywordBlock("WHERE", buildChildren(element, fragments), true, true);
      case "set" -> keywordBlock("SET", buildChildren(element, fragments), true, false);
      case "trim" -> trimBuild(element, fragments);
      case "foreach" -> new BuildResult("(__FOREACH__)", true);
      case "if" -> ifBuild(element, fragments);
      case "choose" -> chooseBuild(element, fragments);
      case "when", "otherwise", "script" -> {
        BuildResult inner = buildChildren(element, fragments);
        yield new BuildResult(inner.sql(), true);
      }
      case "bind" -> new BuildResult("", true);
      default -> {
        BuildResult inner = buildChildren(element, fragments);
        yield new BuildResult(inner.sql(), true);
      }
    };
  }

  private static BuildResult includeBuild(Element element, Map<String, Element> fragments) {
    Element fragment = fragments.get(element.getAttribute("refid"));
    if (fragment == null) {
      return new BuildResult(" __MISSING_INCLUDE__ ", true);
    }
    return buildChildren(fragment, fragments);
  }

  private static BuildResult ifBuild(Element element, Map<String, Element> fragments) {
    BuildResult body = buildChildren(element, fragments);
    String inner = body.sql().trim();
    if (inner.isEmpty()) {
      return new BuildResult("", true);
    }
    return new BuildResult("/*?if*/ " + inner + " /*?endif*/", true);
  }

  private static BuildResult chooseBuild(Element element, Map<String, Element> fragments) {
    List<String> branches = new ArrayList<>();
    for (Element child : childElements(element)) {
      String tag = child.getTagName();
      if (!"when".equals(tag) && !"otherwise".equals(tag)) {
        continue;
      }
      String branch = buildChildren(child, fragments).sql().trim();
      if (!branch.isEmpty()) {
        branches.add("/*?branch*/ " + branch + " /*?endbranch*/");
      }
    }
    if (branches.isEmpty()) {
      BuildResult inner = buildChildren(element, fragments);
      return new BuildResult(inner.sql(), true);
    }
    return new BuildResult("/*?choose*/ " + String.join(" /*?or*/ ", branches) + " /*?endchoose*/", true);
  }

  private static BuildResult trimBuild(Element element, Map<String, Element> fragments) {
    BuildResult children = buildChildren(element, fragments);
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

  private static BuildResult keywordBlock(
      String keyword,
      BuildResult body,
      boolean dynamic,
      boolean stripLeadingBoolean
  ) {
    String sql = stripLeadingBoolean ? stripLeadingBoolean(body.sql()) : stripTrailingComma(body.sql());
    if (sql == null || sql.isBlank()) {
      return new BuildResult("", dynamic || body.dynamic());
    }
    return new BuildResult(keyword + " " + sql, dynamic || body.dynamic());
  }

  private static void appendSql(StringBuilder buffer, String right) {
    if (right == null || right.isBlank()) {
      return;
    }
    if (buffer.length() > 0 && buffer.charAt(buffer.length() - 1) != ' ') {
      buffer.append(' ');
    }
    buffer.append(right);
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
