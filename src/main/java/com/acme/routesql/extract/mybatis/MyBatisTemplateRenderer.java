package com.acme.routesql.extract.mybatis;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.ognl.ASTMethod;
import org.apache.ibatis.ognl.ASTProperty;
import org.apache.ibatis.ognl.ASTConst;
import org.apache.ibatis.ognl.ASTChain;
import org.apache.ibatis.ognl.ASTStaticMethod;
import org.apache.ibatis.ognl.Ognl;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.scripting.xmltags.XMLScriptBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;

/** Render explicit parameter witnesses with native MyBatis; never infer SQL supportability. */
public final class MyBatisTemplateRenderer {
  private static final Set<String> PURE_NODES = new HashSet<>(Arrays.asList(
      "ASTConst", "ASTProperty", "ASTChain", "ASTEq", "ASTNotEq", "ASTAnd", "ASTOr", "ASTNot",
      "ASTLess", "ASTLessEq", "ASTGreater", "ASTGreaterEq", "ASTList", "ASTIn", "ASTNotIn",
      "ASTAdd", "ASTSubtract", "ASTMultiply", "ASTDivide", "ASTRemainder", "ASTNegate",
      "ASTVarRef", "ASTTest"));
  private static final Object UNRESOLVED_VALUE = new Object();

  private MyBatisTemplateRenderer() {}

  public static Map<String, Object> render(Map<String, Object> template,
      Map<String, Object> witness, Map<String, String> helperClassHashes) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("witness", witness);
    result.put("coverage", "PARAMETER_WITNESS_ONLY");
    try {
      if (template == null || !"READY".equals(template.get("status"))) {
        throw new IllegalArgumentException("Template/include context is unresolved");
      }
      requireData(witness);
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
          String.valueOf(template.get("script")).getBytes(StandardCharsets.UTF_8)));
      validateExpressions(document.getDocumentElement(), helperClassHashes, witness);
      Configuration configuration = new Configuration();
      Map<String, Object> strictWitness = strictParameters(witness);
      XNode root = new XNode(new XPathParser(document), document.getDocumentElement(), new Properties());
      BoundSql bound = new XMLScriptBuilder(configuration, root, Map.class)
          .parseScriptNode().getBoundSql(strictWitness);
      List<Object> parameters = new ArrayList<>();
      List<String> properties = new ArrayList<>();
      List<String> types = new ArrayList<>();
      for (ParameterMapping mapping : bound.getParameterMappings()) {
        String property = mapping.getProperty();
        Object source = bound.hasAdditionalParameter(property) ? bound.getAdditionalParameters() : strictWitness;
        if (!hasProperty(configuration, source, property)) {
          throw new IllegalArgumentException("Missing explicit JDBC parameter property: " + property);
        }
        properties.add(property);
        Object value = configuration.newMetaObject(source).getValue(property);
        parameters.add(value);
        types.add(value == null ? "null" : value.getClass().getName());
      }
      result.put("status", "RENDERED");
      result.put("sql", bound.getSql());
      result.put("parameters", parameters);
      result.put("parameterProperties", properties);
      result.put("parameterTypes", types);
      result.put("mybatisCodeSource", XMLScriptBuilder.class.getProtectionDomain().getCodeSource().getLocation().toString());
      result.put("helperClassHashes", helperClassHashes);
    } catch (Exception | LinkageError e) {
      result.put("status", "UNRENDERABLE");
      result.put("error", e.getClass().getName() + ": " + e.getMessage());
    }
    return result;
  }

  private static boolean hasProperty(Configuration configuration, Object source, String property) {
    // An explicitly supplied null parent has MyBatis's native null semantics; an absent key does not.
    if (source == null) return true;
    PropertyTokenizer token = new PropertyTokenizer(property);
    org.apache.ibatis.reflection.MetaObject meta = configuration.newMetaObject(source);
    if (!meta.hasGetter(token.getIndexedName())) return false;
    Object value = meta.getValue(token.getIndexedName());
    return !token.hasNext() || hasProperty(configuration, value, token.getChildren());
  }

  private static Map<String, Object> strictParameters(Map<String, Object> values) {
    Map<String, Object> strict = new ExplicitParameterMap();
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      strict.put(entry.getKey(), strictValue(entry.getValue()));
    }
    return strict;
  }

  @SuppressWarnings("unchecked")
  private static Object strictValue(Object value) {
    if (value instanceof Map) return strictParameters((Map<String, Object>) value);
    if (value instanceof List) {
      List<Object> strict = new ArrayList<>();
      for (Object item : (List<?>) value) strict.add(strictValue(item));
      return strict;
    }
    return value;
  }

  private static final class ExplicitParameterMap extends LinkedHashMap<String, Object> {
    @Override
    public Object get(Object key) {
      if (!containsKey(key)) throw new IllegalArgumentException("Missing explicit parameter property: " + key);
      return super.get(key);
    }
  }

  private static void requireData(Object value) {
    if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return;
    if (value instanceof Map) {
      for (Object entry : ((Map<?, ?>) value).entrySet()) {
        Map.Entry<?, ?> pair = (Map.Entry<?, ?>) entry;
        if (!(pair.getKey() instanceof String)) throw new IllegalArgumentException("Parameter keys must be strings");
        requireData(pair.getValue());
      }
    } else if (value instanceof List) {
      for (Object item : (List<?>) value) requireData(item);
    } else throw new IllegalArgumentException("Parameter witnesses must contain JSON data, not executable objects");
  }

  private static void validateExpressions(Node node, Map<String, String> helpers,
      Map<String, Object> witness) throws Exception {
    if (node instanceof Element) {
      Element element = (Element) node;
      for (String attribute : Arrays.asList("test", "collection", "value")) {
        if (element.hasAttribute(attribute)) validateExpression(element.getAttribute(attribute), helpers, witness);
      }
    } else if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
      // TextSqlNode evaluates ${...} too; subject it to the same bounded expression policy.
      new GenericTokenParser("${", "}", expression -> {
        try { validateExpression(expression, helpers, witness); }
        catch (Exception e) { throw new IllegalArgumentException("Unresolved text expression", e); }
        return "";
      }).parse(node.getTextContent());
    }
    for (int i = 0; i < node.getChildNodes().getLength(); i++) validateExpressions(node.getChildNodes().item(i), helpers, witness);
  }

  public static void validateExpression(String expression, Map<String, String> helpers) throws Exception {
    validateExpression(expression, helpers, java.util.Collections.emptyMap());
  }

  private static void validateExpression(String expression, Map<String, String> helpers,
      Map<String, Object> witness) throws Exception {
    validateNode((org.apache.ibatis.ognl.Node) Ognl.parseExpression(expression), helpers, witness);
  }

  private static void validateNode(org.apache.ibatis.ognl.Node node, Map<String, String> helpers,
      Map<String, Object> witness) throws Exception {
    if (node instanceof ASTStaticMethod) {
      String className = field(node, "_className");
      String method = field(node, "_methodName");
      if (!"Ognl".equals(className) || !("isEmpty".equals(method) || "isNotEmpty".equals(method))) {
        throw new IllegalArgumentException("Unverified static condition helper");
      }
      String expected = helpers == null ? null : helpers.get(className);
      if (expected == null) throw new IllegalArgumentException("Missing verified helper class hash: " + className);
      Class<?> helper = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = helper.getResourceAsStream("/" + className.replace('.', '/') + ".class")) {
        if (in == null) throw new IllegalArgumentException("Helper class bytes unavailable");
        byte[] buffer = new byte[4096]; int n;
        while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
      }
      StringBuilder actual = new StringBuilder();
      for (byte b : digest.digest()) actual.append(String.format("%02x", b & 255));
      if (!expected.equals(actual.toString())) throw new IllegalArgumentException("Helper class hash mismatch");
    } else if (node instanceof ASTMethod) {
      boolean collectionQuery = node.jjtGetNumChildren() == 0
          && Arrays.asList("size", "length", "isEmpty").contains(((ASTMethod) node).getMethodName());
      if (!collectionQuery && !isPureStringComparison((ASTMethod) node, witness)
          && !isLiteralStringSplit((ASTMethod) node, witness)) {
        throw new IllegalArgumentException("Unverified instance method in condition");
      }
    } else if (!PURE_NODES.contains(node.getClass().getSimpleName())) {
      throw new IllegalArgumentException("Unresolved condition expression: " + node.getClass().getSimpleName());
    }
    if (node instanceof ASTProperty && node.jjtGetNumChildren() == 1 && node.jjtGetChild(0) instanceof ASTConst) {
      Object property = ((ASTConst) node.jjtGetChild(0)).getValue();
      if ("class".equals(property) || "classLoader".equals(property)) throw new IllegalArgumentException("Reflective condition property");
    }
    for (int i = 0; i < node.jjtGetNumChildren(); i++) validateNode(node.jjtGetChild(i), helpers, witness);
  }

  private static boolean isPureStringComparison(ASTMethod method, Map<String, Object> witness) {
    if (method.jjtGetNumChildren() != 1
        || !Arrays.asList("equals", "equalsIgnoreCase").contains(method.getMethodName())) return false;
    return hasStringReceiver(method, witness);
  }

  private static boolean isLiteralStringSplit(ASTMethod method, Map<String, Object> witness) {
    if (!"split".equals(method.getMethodName()) || method.jjtGetNumChildren() != 1
        || !(method.jjtGetChild(0) instanceof ASTConst)) return false;
    Object value = ((ASTConst) method.jjtGetChild(0)).getValue();
    if (!(value instanceof String) && !(value instanceof Character)) return false;
    String delimiter = value.toString();
    // String.split accepts a regex. Admit only a single literal character with no regex syntax;
    // do not evaluate a parameter expression or rewrite the delimiter before native MyBatis.
    if (delimiter.length() != 1 || "\\.^$|?*+()[]{}".indexOf(delimiter.charAt(0)) >= 0) return false;
    return hasStringReceiver(method, witness);
  }

  private static boolean hasStringReceiver(ASTMethod method, Map<String, Object> witness) {
    org.apache.ibatis.ognl.Node chain = method.jjtGetParent();
    if (!(chain instanceof ASTChain)) return false;
    Object receiver = witness;
    for (int i = 0; i < chain.jjtGetNumChildren(); i++) {
      org.apache.ibatis.ognl.Node child = chain.jjtGetChild(i);
      // Explicit JSON null is safe to admit for guarded/unselected branches. If reached,
      // the native renderer retains its null error. Absent values and executable objects fail.
      if (child == method) return receiver == null || receiver instanceof String;
      receiver = purePropertyValue(child, receiver);
    }
    return false;
  }

  /** Resolve only JSON/literal property access, never invoke OGNL or a business getter for admission. */
  private static Object purePropertyValue(org.apache.ibatis.ognl.Node node, Object receiver) {
    if (node instanceof ASTConst) return ((ASTConst) node).getValue();
    if (!(node instanceof ASTProperty) || node.jjtGetNumChildren() != 1
        || !(node.jjtGetChild(0) instanceof ASTConst)) return UNRESOLVED_VALUE;
    Object key = ((ASTConst) node.jjtGetChild(0)).getValue();
    if (receiver instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) receiver;
      return map.containsKey(key) ? map.get(key) : UNRESOLVED_VALUE;
    }
    if (receiver instanceof List && key instanceof Number) {
      int index = ((Number) key).intValue();
      List<?> list = (List<?>) receiver;
      return index >= 0 && index < list.size() ? list.get(index) : UNRESOLVED_VALUE;
    }
    return UNRESOLVED_VALUE;
  }

  private static String field(Object node, String name) throws Exception {
    Field field = node.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return (String) field.get(node);
  }
}
