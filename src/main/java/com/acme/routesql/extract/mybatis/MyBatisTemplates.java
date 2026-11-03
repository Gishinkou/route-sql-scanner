package com.acme.routesql.extract.mybatis;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLIncludeTransformer;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Preserve the real template and use MyBatis's namespace/property-aware include expansion. */
public final class MyBatisTemplates {
  private MyBatisTemplates() {}

  public static Map<String, Object> capture(Element statement, Map<String, Element> fragments) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("format", "mybatis-template/v1");
    out.put("sourcePath", statement.getOwnerDocument().getDocumentURI());
    try {
      out.put("statementSha256", sha(xml(statement)));
      String namespace = statement.getOwnerDocument().getDocumentElement().getAttribute("namespace");
      Configuration configuration = new Configuration();
      MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration,
          String.valueOf(statement.getOwnerDocument().getDocumentURI()));
      assistant.setCurrentNamespace(namespace);
      Map<String, Element> canonical = new LinkedHashMap<>();
      Set<String> ambiguous = new LinkedHashSet<>();
      Set<Element> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<Element, Boolean>());
      for (Element fragment : fragments.values()) {
        if (!seen.add(fragment)) continue;
        Element owner = fragment.getOwnerDocument().getDocumentElement();
        String ownerNamespace = owner.getAttribute("namespace");
        String id = fragment.getAttribute("id");
        String key = id.startsWith(ownerNamespace + ".") ? id : ownerNamespace + "." + id;
        Element previous = canonical.putIfAbsent(key, fragment);
        if (previous != null && !xml(previous).equals(xml(fragment))) {
          ambiguous.add(key);
        }
      }
      for (Map.Entry<String, Element> entry : canonical.entrySet()) {
        Element fragment = entry.getValue();
        configuration.getSqlFragments().put(entry.getKey(),
            new XNode(new XPathParser(fragment.getOwnerDocument()), fragment, new Properties()));
      }
      List<Map<String, Object>> includes = new ArrayList<>();
      checkIncludes(statement, assistant, canonical, ambiguous, new Properties(), new LinkedHashSet<>(), includes);
      Element resolved = (Element) statement.cloneNode(true);
      new XMLIncludeTransformer(configuration, assistant).applyIncludes(resolved);
      // selectKey is a separate executable statement, not part of its INSERT's SQL source.
      for (int i = resolved.getChildNodes().getLength() - 1; i >= 0; i--) {
        Node node = resolved.getChildNodes().item(i);
        if (node instanceof Element && "selectKey".equals(node.getNodeName())) resolved.removeChild(node);
      }
      List<Map<String, Object>> conditions = new ArrayList<>();
      conditions(resolved, "/statement", conditions);
      out.put("status", "READY");
      out.put("script", xml(resolved));
      out.put("scriptSha256", sha(xml(resolved)));
      out.put("includes", includes);
      out.put("conditions", conditions);
    } catch (Exception e) {
      out.put("status", "UNRESOLVED");
      out.put("error", e.getClass().getName() + ": " + e.getMessage());
    }
    return out;
  }

  private static void checkIncludes(Node node, MapperBuilderAssistant assistant,
      Map<String, Element> fragments, Set<String> ambiguous, Properties variables, Set<String> stack,
      List<Map<String, Object>> evidence) throws Exception {
    if (node instanceof Element && "include".equals(node.getNodeName())) {
      Element include = (Element) node;
      String refid = PropertyParser.parse(include.getAttribute("refid"), variables);
      String key = assistant.applyCurrentNamespace(refid, true);
      if (ambiguous.contains(key)) throw new IllegalArgumentException("Ambiguous SQL fragment: " + key);
      Element fragment = fragments.get(key);
      if (fragment == null) throw new IllegalArgumentException("Missing SQL fragment: " + key);
      if (stack.size() >= 64 || !stack.add(key)) throw new IllegalArgumentException("Cyclic SQL include: " + key);
      Properties nested = new Properties();
      nested.putAll(variables);
      for (Element property : MyBatisSqlScriptBuilder.childElements(include)) {
        nested.setProperty(property.getAttribute("name"), PropertyParser.parse(property.getAttribute("value"), variables));
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("refid", refid);
      item.put("resolvedId", key);
      item.put("sourcePath", fragment.getOwnerDocument().getDocumentURI());
      item.put("fragmentSha256", sha(xml(fragment)));
      evidence.add(item);
      checkIncludes(fragment, assistant, fragments, ambiguous, nested, stack, evidence);
      stack.remove(key);
      return;
    }
    for (int i = 0; i < node.getChildNodes().getLength(); i++) {
      checkIncludes(node.getChildNodes().item(i), assistant, fragments, ambiguous, variables, stack, evidence);
    }
  }

  private static void conditions(Element parent, String path, List<Map<String, Object>> out) {
    int index = 0;
    for (Element child : MyBatisSqlScriptBuilder.childElements(parent)) {
      String childPath = path + "/" + child.getTagName() + "[" + (++index) + "]";
      if (child.hasAttribute("test")) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("path", childPath);
        condition.put("test", child.getAttribute("test"));
        out.add(condition);
      }
      conditions(child, childPath, out);
    }
  }

  static String xml(Node node) throws Exception {
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
    StringWriter text = new StringWriter();
    transformer.transform(new DOMSource(node), new StreamResult(text));
    return text.toString();
  }

  static String sha(String text) throws Exception {
    byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) out.append(String.format("%02x", b & 255));
    return out.toString();
  }
}
