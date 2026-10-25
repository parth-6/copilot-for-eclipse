// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.lsp.protocol.byok;

/**
 * Custom endpoint BYOK provider configuration.
 *
 * @param providerName provider key
 * @param apiKey       provider API key
 * @param groupName    provider display name
 * @param apiType      request format, such as chatCompletions
 */
public record ByokCustomProviderConfig(String providerName, String apiKey, String groupName, String apiType) {
}