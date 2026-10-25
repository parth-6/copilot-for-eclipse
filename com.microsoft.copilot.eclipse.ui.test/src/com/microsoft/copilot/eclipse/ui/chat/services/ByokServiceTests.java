// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.ui.chat.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.microsoft.copilot.eclipse.core.lsp.CopilotLanguageServerConnection;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokApiKey;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListApiKeyResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListModelResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListProviderConfigParams;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokListProviderConfigResponse;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokModel;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokModelProvider;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokProviderConfig;
import com.microsoft.copilot.eclipse.core.lsp.protocol.byok.ByokStatusResponse;
import com.microsoft.copilot.eclipse.ui.preferences.ByokPreferencePage;

@ExtendWith(MockitoExtension.class)
class ByokServiceTests {

  private static final long WAIT_TIMEOUT_MS = 5000;
  private static final String OLLAMA_ENDPOINT = "http://localhost:11434";
  private static final String OLLAMA_PROVIDER = ByokModelProvider.OLLAMA.getDisplayName();
  private static final String NVIDIA_NIM_PROVIDER = ByokModelProvider.getNvidiaNimProviderName();
  private static final String NVIDIA_NIM_ENDPOINT = "https://integrate.api.nvidia.com/v1";
  private static final String NVIDIA_API_KEY = "nvapi-test-key-12345";

  @Mock
  private CopilotLanguageServerConnection lsConnection;

  @Mock
  private ByokPreferencePage preferencePage;

  private ByokService byokService;

  @BeforeEach
  void setUp() {
    byokService = new ByokService(lsConnection);
    byokService.bindByokPreferencePage(preferencePage);
    clearInvocations(preferencePage);
  }

  @AfterEach
  void tearDown() {
    byokService.ensureRealm(byokService::dispose);
  }

  @Test
  void testConfigureOllama_discoveryFailureKeepsSavedEndpointVisible() {
    when(lsConnection.saveByokProviderConfig(any())).thenReturn(completedStatus());
    when(lsConnection.listByokModels(any()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Ollama is unavailable")));

    assertThrows(CompletionException.class, () -> byokService.configureOllama(OLLAMA_ENDPOINT).join());

    verify(preferencePage, timeout(WAIT_TIMEOUT_MS)).updateProviderUrlsDisplay(argThat(
        providerUrls -> OLLAMA_ENDPOINT.equals(providerUrls.get(OLLAMA_PROVIDER))));
  }

  @Test
  void testLoadProviderUrls_ignoresBlankUrlsAndKeepsFirstDuplicate() {
    String duplicateEndpoint = "http://localhost:11435";
    when(lsConnection.listByokProviderConfigs(any(ByokListProviderConfigParams.class)))
        .thenReturn(CompletableFuture.completedFuture(new ByokListProviderConfigResponse(List.of(
            new ByokProviderConfig(OLLAMA_PROVIDER, " "),
            new ByokProviderConfig(OLLAMA_PROVIDER, OLLAMA_ENDPOINT),
            new ByokProviderConfig(OLLAMA_PROVIDER, duplicateEndpoint)))));

    byokService.loadProviderUrls().join();

    verify(preferencePage, timeout(WAIT_TIMEOUT_MS))
        .updateProviderUrlsDisplay(Map.of(OLLAMA_PROVIDER, OLLAMA_ENDPOINT));
  }

  @Test
  void testConfigureOllama_emptyDiscoveryRefreshesLocalModels() {
    when(lsConnection.saveByokProviderConfig(any())).thenReturn(completedStatus());
    configureRefreshResponses(List.of(), OLLAMA_PROVIDER, OLLAMA_ENDPOINT);

    byokService.configureOllama(OLLAMA_ENDPOINT).join();

    verify(lsConnection, times(2)).listByokModels(any());
    verify(lsConnection).listByokModels(argThat(params -> Boolean.FALSE.equals(params.getEnableFetchUrl())));
  }

  @Test
  void testConfigureOllama_discoveredModelsAreRegistered() {
    ByokModel discoveredModel = new ByokModel();
    discoveredModel.setProviderName(OLLAMA_PROVIDER);
    discoveredModel.setModelId("qwen3.5:0.8b");
    discoveredModel.setRegistered(false);
    when(lsConnection.saveByokProviderConfig(any())).thenReturn(completedStatus());
    configureRefreshResponses(List.of(discoveredModel), OLLAMA_PROVIDER, OLLAMA_ENDPOINT);
    when(lsConnection.saveByokModel(any())).thenReturn(completedStatus());

    byokService.configureOllama(OLLAMA_ENDPOINT).join();

    ArgumentCaptor<ByokModel> modelCaptor = ArgumentCaptor.forClass(ByokModel.class);
    verify(lsConnection).saveByokModel(modelCaptor.capture());
    assertTrue(modelCaptor.getValue().isRegistered());
  }

  @Test
  void testDeleteOllamaConfig_removesEndpointBeforeRefresh() {
    when(lsConnection.deleteByokProviderConfig(any())).thenReturn(completedStatus());
    configureRefreshResponses(List.of(), OLLAMA_PROVIDER, OLLAMA_ENDPOINT);
    byokService.loadProviderUrls().join();
    clearInvocations(preferencePage);

    byokService.deleteOllamaConfig().join();

    verify(preferencePage, timeout(WAIT_TIMEOUT_MS)).updateProviderUrlsDisplay(argThat(Map::isEmpty));
  }

  private void configureRefreshResponses(List<ByokModel> discoveredModels, String providerName, String endpointUrl) {
    when(lsConnection.listByokModels(any())).thenAnswer(invocation -> {
      ByokListModelResponse response = new ByokListModelResponse();
      response.setModels(discoveredModels);
      return CompletableFuture.completedFuture(response);
    });
    when(lsConnection.listByokApiKeys(any(ByokApiKey.class)))
        .thenReturn(CompletableFuture.completedFuture(new ByokListApiKeyResponse(List.of())));
    when(lsConnection.listByokProviderConfigs(any(ByokListProviderConfigParams.class)))
        .thenReturn(CompletableFuture.completedFuture(new ByokListProviderConfigResponse(
            List.of(new ByokProviderConfig(providerName, endpointUrl)))));
  }

  private CompletableFuture<ByokStatusResponse> completedStatus() {
    ByokStatusResponse response = new ByokStatusResponse();
    response.setSuccess(true);
    return CompletableFuture.completedFuture(response);
  }

  @Test
  void testConfigureNvidiaNim_success() {
    when(lsConnection.saveByokCustomProviderConfig(any())).thenReturn(completedStatus());
    configureRefreshResponses(List.of(), NVIDIA_NIM_PROVIDER, NVIDIA_NIM_ENDPOINT);
    when(lsConnection.saveByokModel(any())).thenReturn(completedStatus());

    byokService.configureNvidiaNim(NVIDIA_API_KEY).join();

    verify(lsConnection).saveByokCustomProviderConfig(argThat(config ->
      NVIDIA_NIM_PROVIDER.equals(config.providerName()) && NVIDIA_API_KEY.equals(config.apiKey())
        && ByokModelProvider.NVIDIA_NIM.getDisplayName().equals(config.groupName())
        && "chatCompletions".equals(config.apiType())));
    verify(lsConnection, never()).saveByokApiKey(any());
    verify(lsConnection, never()).saveByokProviderConfig(any());
    ArgumentCaptor<ByokModel> modelCaptor = ArgumentCaptor.forClass(ByokModel.class);
    verify(lsConnection).saveByokModel(modelCaptor.capture());
    ByokModel model = modelCaptor.getValue();
    assertEquals("nvidia/nemotron-3-ultra-550b-a55b", model.getModelId());
    assertEquals(NVIDIA_NIM_ENDPOINT, model.getDeploymentUrl());
    assertTrue(model.isRegistered());
    assertTrue(model.isCustomModel());
    assertEquals("Nemotron 3 Ultra 550B A55B", model.getModelCapabilities().getName());
    assertEquals(900000, model.getModelCapabilities().getMaxInputTokens());
    assertEquals(100000, model.getModelCapabilities().getMaxOutputTokens());
    assertTrue(model.getModelCapabilities().isToolCalling());
    assertTrue(!model.getModelCapabilities().isVision());
  }

  @Test
  void testSaveAdditionalNvidiaNimModelId() {
    String additionalModelId = "nvidia/nemotron-3.5-super-120b-a12b";
    ByokModel model = new ByokModel();
    model.setProviderName(NVIDIA_NIM_PROVIDER);
    model.setModelId(additionalModelId);
    model.setRegistered(true);
    model.setCustomModel(true);
    model.setDeploymentUrl(NVIDIA_NIM_ENDPOINT);
    when(lsConnection.saveByokModel(any())).thenReturn(completedStatus());
    ByokListModelResponse modelsResponse = new ByokListModelResponse();
    modelsResponse.setModels(List.of(model));
    when(lsConnection.listByokModels(any())).thenReturn(CompletableFuture.completedFuture(modelsResponse));

    byokService.saveModel(model).join();

    ArgumentCaptor<ByokModel> modelCaptor = ArgumentCaptor.forClass(ByokModel.class);
    verify(lsConnection).saveByokModel(modelCaptor.capture());
    assertEquals(additionalModelId, modelCaptor.getValue().getModelId());
    assertEquals(NVIDIA_NIM_PROVIDER, modelCaptor.getValue().getProviderName());
    assertEquals(NVIDIA_NIM_ENDPOINT, modelCaptor.getValue().getDeploymentUrl());
  }

  @Test
  void testConfigureNvidiaNim_apiKeyFailureDoesNotSaveModel() {
    when(lsConnection.saveByokCustomProviderConfig(any()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Invalid API key")));

    assertThrows(CompletionException.class, () -> byokService.configureNvidiaNim(NVIDIA_API_KEY).join());

    verify(lsConnection, never()).saveByokModel(any());
  }

  @Test
  void testConfigureNvidiaNim_doesNotFetchModelsFromProvider() {
    when(lsConnection.saveByokCustomProviderConfig(any())).thenReturn(completedStatus());
    configureRefreshResponses(List.of(), NVIDIA_NIM_PROVIDER, NVIDIA_NIM_ENDPOINT);
    when(lsConnection.saveByokModel(any())).thenReturn(completedStatus());

    byokService.configureNvidiaNim(NVIDIA_API_KEY).join();

    verify(lsConnection, times(1)).listByokModels(any());
    verify(lsConnection).listByokModels(argThat(params -> Boolean.FALSE.equals(params.getEnableFetchUrl())));
    verify(lsConnection, never()).listByokModels(argThat(params -> Boolean.TRUE.equals(params.getEnableFetchUrl())));
  }

  @Test
  void testDeleteNvidiaNimConfig_removesModelAndApiKey() {
    when(lsConnection.deleteByokModel(any())).thenReturn(completedStatus());
    when(lsConnection.deleteByokApiKey(any())).thenReturn(completedStatus());
    ByokModel firstModel = new ByokModel();
    firstModel.setProviderName(NVIDIA_NIM_PROVIDER);
    firstModel.setModelId("nvidia/nemotron-3-ultra-550b-a55b");
    ByokModel secondModel = new ByokModel();
    secondModel.setProviderName(NVIDIA_NIM_PROVIDER);
    secondModel.setModelId("nvidia/nemotron-3-super-120b-a12b");
    when(lsConnection.listByokApiKeys(any(ByokApiKey.class)))
        .thenReturn(CompletableFuture.completedFuture(new ByokListApiKeyResponse(List.of())));
    when(lsConnection.listByokProviderConfigs(any(ByokListProviderConfigParams.class)))
        .thenReturn(CompletableFuture.completedFuture(new ByokListProviderConfigResponse(List.of())));
    ByokListModelResponse modelsResponse = new ByokListModelResponse();
    modelsResponse.setModels(List.of(firstModel, secondModel));
    when(lsConnection.listByokModels(any())).thenReturn(CompletableFuture.completedFuture(modelsResponse));
    byokService.loadProviderUrls().join();
    clearInvocations(preferencePage);

    byokService.deleteNvidiaNimConfig().join();

    verify(lsConnection, times(2)).deleteByokModel(argThat(model ->
      NVIDIA_NIM_PROVIDER.equals(model.getProviderName()) && model.getModelId() != null));
    verify(lsConnection).deleteByokApiKey(argThat(key -> NVIDIA_NIM_PROVIDER.equals(key.getProviderName())));
    verify(preferencePage, timeout(WAIT_TIMEOUT_MS).atLeastOnce())
      .updateProviderUrlsDisplay(argThat(Map::isEmpty));
  }
}
