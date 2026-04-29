package com.acme.routesql.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public interface ConfigPreprocessor {
  boolean supports(JsonNode input);

  JsonNode preprocess(JsonNode input, ObjectMapper mapper);
}
