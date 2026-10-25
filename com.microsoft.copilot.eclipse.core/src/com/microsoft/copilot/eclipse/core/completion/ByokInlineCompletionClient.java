// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.completion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import com.microsoft.copilot.eclipse.core.Constants;
import com.microsoft.copilot.eclipse.core.CopilotCore;
import com.microsoft.copilot.eclipse.core.lsp.CopilotLanguageServerConnection;
import com.microsoft.copilot.eclipse.core.lsp.protocol.CompletionDocument;
import com.microsoft.copilot.eclipse.core.lsp.protocol.CompletionItem;
import com.microsoft.copilot.eclipse.core.lsp.protocol.CompletionResult;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokApiKey;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokCustomProviderConfigInfo;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListApiKeyResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListCustomProviderConfigsParams;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListCustomProviderConfigsResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListModelParams;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListModelResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListProviderConfigParams;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListProviderConfigResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokModel;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokModelProvider;

/**
 * Requests inline code completions from a selected custom BYOK endpoint.
 */
public class ByokInlineCompletionClient {

  private static final long CONFIGURATION_CACHE_MILLIS = 30_000;
  private static final long LOCAL_REQUEST_TIMEOUT_SECONDS = 1;
  private static final int CONNECTION_TIMEOUT_MILLIS = 2_000;
  private static final int READ_TIMEOUT_MILLIS = 4_000;
  private static final int MAX_CONTEXT_CHARS = 4_000;
  private static final int MAX_OUTPUT_TOKENS = 256;

  private final CopilotLanguageServerConnection lsConnection;
  private final HttpTransport httpTransport;
  private CachedConfiguration cachedConfiguration;

  /**
   * Creates a completion client using the default HTTP transport.
   */
  public ByokInlineCompletionClient(CopilotLanguageServerConnection lsConnection) {
    this(lsConnection, ByokInlineCompletionClient::postJson);
  }

  /**
   * Creates a completion client with a replaceable HTTP transport for tests.
   */
  ByokInlineCompletionClient(CopilotLanguageServerConnection lsConnection, HttpTransport httpTransport) {
    this.lsConnection = lsConnection;
    this.httpTransport = httpTransport;
  }

  /**
   * Request a completion for the selected custom BYOK model.
   */
  public CompletionResult complete(CompletionDocument document, String source, String fileName,
      String providerName, String modelId) throws Exception {
    CachedConfiguration config = resolveConfiguration(providerName, modelId);
    if (config == null) {
      return new CompletionResult(List.of());
    }

    int cursorOffset = positionToOffset(source, document.getPosition());
    String prefix = source.substring(Math.max(0, cursorOffset - MAX_CONTEXT_CHARS), cursorOffset);
    String suffix = source.substring(cursorOffset, Math.min(source.length(), cursorOffset + MAX_CONTEXT_CHARS));
    String prompt = createPrompt(fileName, prefix, suffix);
    ApiType apiType = ApiType.parse(config.apiType());
    JsonObject payload = createPayload(apiType, modelId, prompt);
    String endpoint = resolveEndpoint(config.deploymentUrl(), apiType);
    String completionText = extractCompletion(apiType,
        httpTransport.post(endpoint, config.apiKey(), apiType, payload));
    if (completionText == null || completionText.isBlank()) {
      return new CompletionResult(List.of());
    }

    Position position = document.getPosition();
    CompletionItem item = new CompletionItem(UUID.randomUUID().toString(), completionText,
        new Range(position, position), completionText, position, document.getVersion());
    return new CompletionResult(List.of(item));
  }

  private synchronized CachedConfiguration resolveConfiguration(String providerName, String modelId)
      throws Exception {
    long now = System.currentTimeMillis();
    if (cachedConfiguration != null && cachedConfiguration.expiresAt() > now
        && cachedConfiguration.providerName().equals(providerName) && cachedConfiguration.modelId().equals(modelId)) {
      return cachedConfiguration;
    }
    boolean ollama = ByokModelProvider.isOllama(providerName);
    if (ByokModelProvider.isBuiltInProvider(providerName) && !ollama) {
      return null;
    }

    ByokListModelResponse models = lsConnection.listByokModels(new ByokListModelParams(providerName, false))
        .get(LOCAL_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    ByokModel model = models == null || models.getModels() == null ? null
        : models.getModels().stream().filter(candidate -> modelId.equals(candidate.getModelId()))
            .filter(ByokModel::isRegistered)
            .filter(candidate -> ollama || candidate.isCustomModel())
            .findFirst().orElse(null);
    if (model == null) {
      return null;
    }

    String endpoint = model.getDeploymentUrl();
    String apiKey = "";
    String apiType;
    if (ollama) {
      ByokListProviderConfigResponse providerConfigs = lsConnection.listByokProviderConfigs(
          new ByokListProviderConfigParams(providerName)).get(LOCAL_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      endpoint = providerConfigs == null || providerConfigs.providers() == null ? null
          : providerConfigs.providers().stream().filter(config -> providerName.equals(config.providerName()))
              .map(config -> config.url()).filter(url -> url != null && !url.isBlank()).findFirst().orElse(null);
      if (endpoint == null) {
        return null;
      }
      apiType = "chatCompletions";
    } else {
      if (endpoint == null || endpoint.isBlank()) {
        return null;
      }
      ByokListApiKeyResponse keys = lsConnection.listByokApiKeys(new ByokApiKey(providerName, null))
          .get(LOCAL_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      apiKey = keys == null || keys.getApiKeys() == null ? null
          : keys.getApiKeys().stream().filter(key -> providerName.equals(key.getProviderName()))
              .map(ByokApiKey::getApiKey).filter(key -> key != null && !key.isBlank()).findFirst().orElse(null);
      if (apiKey == null) {
        return null;
      }

      ByokListCustomProviderConfigsResponse customConfigs = lsConnection.listByokCustomProviderConfigs(
          new ByokListCustomProviderConfigsParams(providerName)).get(LOCAL_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      apiType = customConfigs == null || customConfigs.providers() == null ? null
          : customConfigs.providers().stream().filter(config -> providerName.equals(config.providerName()))
              .map(ByokCustomProviderConfigInfo::apiType).filter(type -> type != null && !type.isBlank())
              .findFirst().orElse(null);
      if (apiType == null) {
        apiType = "chatCompletions";
      }
    }

    cachedConfiguration = new CachedConfiguration(providerName, modelId, endpoint, apiKey, apiType,
        now + CONFIGURATION_CACHE_MILLIS);
    return cachedConfiguration;
  }

  private static int positionToOffset(String source, Position position) {
    int line = 0;
    int offset = 0;
    while (line < position.getLine() && offset < source.length()) {
      int newline = source.indexOf('\n', offset);
      if (newline < 0) {
        return source.length();
      }
      offset = newline + 1;
      line++;
    }
    return Math.min(source.length(), offset + position.getCharacter());
  }

  private static String createPrompt(String fileName, String prefix, String suffix) {
    String language = fileName == null || fileName.isBlank() ? "code" : fileName;
    return "Complete the " + language + " at the cursor. Return only the code to insert, with no explanation or "
        + "markdown. Do not repeat the suffix.\n\n<PREFIX>\n" + prefix + "\n<CURSOR>\n<SUFFIX>\n" + suffix;
  }

  private static JsonObject createPayload(ApiType apiType, String modelId, String prompt) {
    JsonObject payload = new JsonObject();
    payload.addProperty("model", modelId);
    switch (apiType) {
      case CHAT_COMPLETIONS -> {
        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "You are an inline code completion engine.");
        messages.add(systemMessage);
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);
        payload.add("messages", messages);
        payload.addProperty("max_tokens", MAX_OUTPUT_TOKENS);
        payload.addProperty("temperature", 0.1);
        payload.addProperty("stream", false);
      }
      case RESPONSES -> {
        payload.addProperty("instructions", "You are an inline code completion engine. Return only code to insert.");
        payload.addProperty("input", prompt);
        payload.addProperty("max_output_tokens", MAX_OUTPUT_TOKENS);
        payload.addProperty("temperature", 0.1);
      }
      case MESSAGES -> {
        payload.addProperty("system", "You are an inline code completion engine. Return only code to insert.");
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);
        payload.add("messages", messages);
        payload.addProperty("max_tokens", MAX_OUTPUT_TOKENS);
        payload.addProperty("temperature", 0.1);
      }
      default -> throw new IllegalStateException("Unsupported BYOK API type: " + apiType);
    }
    return payload;
  }

  private static String resolveEndpoint(String deploymentUrl, ApiType apiType) {
    String endpoint = deploymentUrl.trim().replaceAll("/+$", "");
    String[] existingPaths = { "/chat/completions", "/v1/messages", "/messages", "/responses" };
    for (String path : existingPaths) {
      if (endpoint.endsWith(path)) {
        endpoint = endpoint.substring(0, endpoint.length() - path.length());
        break;
      }
    }
    boolean hasVersion = endpoint.matches(".*?/v[0-9]+$");
    String unversionedBase = hasVersion ? endpoint.replaceFirst("/v[0-9]+$", "") : endpoint;
    String versionedBase = hasVersion ? endpoint : endpoint + "/v1";
    return switch (apiType) {
      case CHAT_COMPLETIONS -> versionedBase + "/chat/completions";
      case RESPONSES -> versionedBase + "/responses";
      case MESSAGES -> unversionedBase + "/v1/messages";
      default -> throw new IllegalStateException("Unsupported BYOK API type: " + apiType);
    };
  }

  private static String extractCompletion(ApiType apiType, String responseBody) {
    JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
    if (apiType == ApiType.CHAT_COMPLETIONS) {
      JsonArray choices = response.getAsJsonArray("choices");
      if (choices == null || choices.isEmpty()) {
        return "";
      }
      JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
      return message == null ? "" : extractText(message.get("content"));
    }
    if (apiType == ApiType.RESPONSES) {
      if (response.has("output_text")) {
        return extractText(response.get("output_text"));
      }
      JsonArray output = response.getAsJsonArray("output");
      if (output != null) {
        StringBuilder text = new StringBuilder();
        for (JsonElement outputItem : output) {
          JsonArray content = outputItem.getAsJsonObject().getAsJsonArray("content");
          if (content != null) {
            for (JsonElement contentItem : content) {
              JsonObject part = contentItem.getAsJsonObject();
              if (part.has("text")) {
                text.append(part.get("text").getAsString());
              }
            }
          }
        }
        return text.toString();
      }
      return "";
    }
    JsonArray content = response.getAsJsonArray("content");
    return content == null || content.isEmpty() ? ""
        : extractText(content.get(0).getAsJsonObject().get("text"));
  }

  private static String extractText(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return "";
    }
    if (element.isJsonPrimitive()) {
      return element.getAsString();
    }
    if (element.isJsonArray()) {
      StringBuilder text = new StringBuilder();
      for (JsonElement part : element.getAsJsonArray()) {
        if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
          text.append(part.getAsJsonObject().get("text").getAsString());
        }
      }
      return text.toString();
    }
    return "";
  }

  private static String postJson(String endpoint, String apiKey, ApiType apiType, JsonObject payload)
      throws IOException {
    HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
    connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLIS);
    connection.setReadTimeout(READ_TIMEOUT_MILLIS);
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setRequestProperty("Accept", "application/json");
    if (apiType == ApiType.MESSAGES) {
      connection.setRequestProperty("x-api-key", apiKey);
      connection.setRequestProperty("anthropic-version", "2023-06-01");
    } else if (apiKey != null && !apiKey.isBlank()) {
      connection.setRequestProperty("Authorization", "Bearer " + apiKey);
    }
    byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
    connection.setFixedLengthStreamingMode(body.length);
    try (OutputStream output = connection.getOutputStream()) {
      output.write(body);
    }
    int status = connection.getResponseCode();
    if (status < 200 || status >= 300) {
      throw new IOException("BYOK completion endpoint returned HTTP " + status);
    }
    try (InputStream input = connection.getInputStream()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } finally {
      connection.disconnect();
    }
  }

  @FunctionalInterface
  interface HttpTransport {
    String post(String endpoint, String apiKey, ApiType apiType, JsonObject payload) throws IOException;
  }

  enum ApiType {
    CHAT_COMPLETIONS,
    RESPONSES,
    MESSAGES;

    static ApiType parse(String value) {
      if ("responses".equalsIgnoreCase(value)) {
        return RESPONSES;
      }
      if ("messages".equalsIgnoreCase(value)) {
        return MESSAGES;
      }
      return CHAT_COMPLETIONS;
    }
  }

  private record CachedConfiguration(String providerName, String modelId, String deploymentUrl, String apiKey,
      String apiType, long expiresAt) {
  }
}