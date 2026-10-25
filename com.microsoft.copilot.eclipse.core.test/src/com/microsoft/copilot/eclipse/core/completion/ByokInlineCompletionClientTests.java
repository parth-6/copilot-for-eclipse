// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.gson.JsonObject;
import com.microsoft.copilot.eclipse.core.lsp.CopilotLanguageServerConnection;
import com.microsoft.copilot.eclipse.core.lsp.protocol.CompletionDocument;
import com.microsoft.copilot.eclipse.core.lsp.protocol.CompletionResult;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokApiKey;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokCustomProviderConfigInfo;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListApiKeyResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListCustomProviderConfigsResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListModelResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListProviderConfigParams;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListProviderConfigResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokModel;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokModelProvider;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokProviderConfig;

@ExtendWith(MockitoExtension.class)
class ByokInlineCompletionClientTests {

  private static final String PROVIDER_NAME = "MyCustomEndpoint";
  private static final String MODEL_ID = "completion-model";
  private static final String API_KEY = "test-api-key";
  private static final String BASE_URL = "https://models.example.test/v1";

  @Mock
  private CopilotLanguageServerConnection lsConnection;

  @Test
  void chatCompletionsUsesSelectedModelAndPrefixSuffixContext() throws Exception {
    configureModel("chatCompletions");
    AtomicReference<JsonObject> request = new AtomicReference<>();
    ByokInlineCompletionClient client = new ByokInlineCompletionClient(lsConnection,
        (endpoint, apiKey, apiType, payload) -> {
          assertEquals(BASE_URL + "/chat/completions", endpoint);
          assertEquals(API_KEY, apiKey);
          assertEquals(ByokInlineCompletionClient.ApiType.CHAT_COMPLETIONS, apiType);
          request.set(payload);
          return "{\"choices\":[{\"message\":{\"content\":\"completion\"}}]}";
        });

    CompletionResult result = client.complete(document(), "prefixSUFFIX", "Sample.java", PROVIDER_NAME, MODEL_ID);

    assertEquals(MODEL_ID, request.get().get("model").getAsString());
    assertEquals(2, request.get().getAsJsonArray("messages").size());
    String prompt = request.get().getAsJsonArray("messages").get(1).getAsJsonObject()
        .get("content").getAsString();
    assertTrue(prompt.contains("prefix"));
    assertTrue(prompt.contains("SUFFIX"));
    assertEquals("completion", result.getCompletions().get(0).getText());
    assertEquals(11, result.getCompletions().get(0).getDocVersion());
  }

  @Test
  void responsesUsesResponsesEndpointAndExtractsOutputText() throws Exception {
    configureModel("responses");
    ByokInlineCompletionClient client = new ByokInlineCompletionClient(lsConnection,
        (endpoint, apiKey, apiType, payload) -> {
          assertEquals(BASE_URL + "/responses", endpoint);
            String input = payload.get("input").getAsString();
            assertTrue(input.substring(input.indexOf("<PREFIX>"), input.indexOf("<CURSOR>")).contains("prefix"));
            return "{\"output_text\":\"response completion\"}";
        });

    CompletionResult result = client.complete(document(), "prefixSUFFIX", "Sample.java", PROVIDER_NAME, MODEL_ID);

    assertEquals("response completion", result.getCompletions().get(0).getDisplayText());
  }

  @Test
  void messagesUsesMessagesEndpointAndExtractsTextBlocks() throws Exception {
    configureModel("messages");
    ByokInlineCompletionClient client = new ByokInlineCompletionClient(lsConnection,
        (endpoint, apiKey, apiType, payload) -> {
          assertEquals(BASE_URL + "/messages", endpoint);
          assertEquals("You are an inline code completion engine. Return only code to insert.",
              payload.get("system").getAsString());
          return "{\"content\":[{\"type\":\"text\",\"text\":\"message completion\"}]}";
        });

    CompletionResult result = client.complete(document(), "prefixSUFFIX", "Sample.java", PROVIDER_NAME, MODEL_ID);

    assertEquals("message completion", result.getCompletions().get(0).getText());
  }

  @Test
  void missingRegisteredModelReturnsNoCompletionWithoutCallingEndpoint() throws Exception {
    ByokListModelResponse modelResponse = new ByokListModelResponse();
    modelResponse.setModels(List.of());
    when(lsConnection.listByokModels(any())).thenReturn(CompletableFuture.completedFuture(modelResponse));
    ByokInlineCompletionClient client = new ByokInlineCompletionClient(lsConnection,
        (endpoint, apiKey, apiType, payload) -> {
          throw new AssertionError("No endpoint request should be made without a model");
        });

    CompletionResult result = client.complete(document(), "prefixSUFFIX", "Sample.java", PROVIDER_NAME, MODEL_ID);

    assertTrue(result.getCompletions().isEmpty());
    verify(lsConnection, never()).listByokApiKeys(any());
  }

  @Test
  void nvidiaNimIsTreatedAsACustomEndpoint() {
    assertTrue(!ByokModelProvider.isBuiltInProvider(ByokModelProvider.getNvidiaNimProviderName()));
  }

  @Test
  void ollamaUsesConfiguredLocalUrlWithoutLookingUpAnApiKey() throws Exception {
    String providerName = ByokModelProvider.OLLAMA.getDisplayName();
    String modelId = "qwen2.5-coder:7b";
    String endpoint = "http://localhost:11434";
    ByokModel model = new ByokModel();
    model.setProviderName(providerName);
    model.setModelId(modelId);
    model.setRegistered(true);
    ByokListModelResponse modelResponse = new ByokListModelResponse();
    modelResponse.setModels(List.of(model));
    when(lsConnection.listByokModels(any())).thenReturn(CompletableFuture.completedFuture(modelResponse));
    when(lsConnection.listByokProviderConfigs(any(ByokListProviderConfigParams.class)))
        .thenReturn(CompletableFuture.completedFuture(new ByokListProviderConfigResponse(
            List.of(new ByokProviderConfig(providerName, endpoint)))));
    ByokInlineCompletionClient client = new ByokInlineCompletionClient(lsConnection,
        (requestUrl, apiKey, apiType, payload) -> {
          assertEquals(endpoint + "/v1/chat/completions", requestUrl);
          assertEquals("", apiKey);
          assertEquals(ByokInlineCompletionClient.ApiType.CHAT_COMPLETIONS, apiType);
          return "{\"choices\":[{\"message\":{\"content\":\"local completion\"}}]}";
        });

    CompletionResult result = client.complete(document(), "prefixSUFFIX", "Sample.java", providerName, modelId);

    assertEquals("local completion", result.getCompletions().get(0).getText());
    verify(lsConnection, never()).listByokApiKeys(any());
    verify(lsConnection, never()).listByokCustomProviderConfigs(any());
  }

  private void configureModel(String apiType) {
    ByokModel model = new ByokModel();
    model.setProviderName(PROVIDER_NAME);
    model.setModelId(MODEL_ID);
    model.setCustomModel(true);
    model.setRegistered(true);
    model.setDeploymentUrl(BASE_URL);
    ByokListModelResponse modelResponse = new ByokListModelResponse();
    modelResponse.setModels(List.of(model));
    when(lsConnection.listByokModels(any())).thenReturn(CompletableFuture.completedFuture(modelResponse));

    ByokApiKey apiKey = new ByokApiKey(PROVIDER_NAME, null);
    apiKey.setApiKey(API_KEY);
    when(lsConnection.listByokApiKeys(any()))
        .thenReturn(CompletableFuture.completedFuture(new ByokListApiKeyResponse(List.of(apiKey))));
    when(lsConnection.listByokCustomProviderConfigs(any()))
        .thenReturn(CompletableFuture.completedFuture(new ByokListCustomProviderConfigsResponse(
            List.of(new ByokCustomProviderConfigInfo(PROVIDER_NAME, "Custom", apiType)))));
  }

  private static CompletionDocument document() {
    CompletionDocument document = new CompletionDocument("file:///Sample.java", new Position(0, 6));
    document.setVersion(11);
    return document;
  }
}
