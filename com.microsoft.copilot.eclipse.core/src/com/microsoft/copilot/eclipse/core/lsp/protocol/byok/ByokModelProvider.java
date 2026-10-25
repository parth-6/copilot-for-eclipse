// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.lsp.protocol.byok;

/**
 * Enum representing BYOK model providers.
 */
public enum ByokModelProvider {
  AZURE("Azure"),
  OPENAI("OpenAI"),
  GEMINI("Gemini"),
  GROQ("Groq"),
  OPENROUTER("OpenRouter"),
  ANTHROPIC("Anthropic"),
  OLLAMA("Ollama"),
  NVIDIA_NIM("NVIDIA NIM");


  private final String displayName;

  ByokModelProvider(String displayName) {
    this.displayName = displayName;
  }

  public String getDisplayName() {
    return displayName;
  }

  /**
   * Utility to check if a provider display name corresponds to AZURE.
   * This avoids scattering direct enum displayName comparisons across UI code.
   */
  public static boolean isAzure(String providerDisplayName) {
    return AZURE.getDisplayName().equals(providerDisplayName);
  }

  /**
   * Utility to check if a provider display name corresponds to Ollama.
   */
  public static boolean isOllama(String providerDisplayName) {
    return OLLAMA.getDisplayName().equals(providerDisplayName);
  }

  /**
   * Utility to check if a provider display name corresponds to NVIDIA NIM.
   */
  public static boolean isNvidiaNim(String providerDisplayName) {
    return NVIDIA_NIM.getDisplayName().equals(providerDisplayName)
        || getNvidiaNimProviderName().equals(providerDisplayName);
  }

  /**
   * Returns the provider key used by the bundled agent's custom endpoint configuration.
   */
  public static String getNvidiaNimProviderName() {
    return "NvidiaNim";
  }

  /**
   * Returns the fixed endpoint URL for NVIDIA NIM hosted API.
   */
  public static String getNvidiaNimEndpoint() {
    return "https://integrate.api.nvidia.com/v1";
  }

  /**
   * Returns whether the provider requires a provider-level API key.
   */
  public static boolean requiresApiKey(String providerDisplayName) {
    return !isAzure(providerDisplayName) && !isOllama(providerDisplayName);
  }

  @Override
  public String toString() {
    return displayName;
  }
}
