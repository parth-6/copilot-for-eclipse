// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.lsp.protocol.byok;

/**
 * Stored custom endpoint provider configuration metadata.
 *
 * @param providerName provider key
 * @param groupName    provider display name
 * @param apiType      configured request format
 */
public record ByokCustomProviderConfigInfo(String providerName, String groupName, String apiType) {
}