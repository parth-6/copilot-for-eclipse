// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.lsp.protocol.byok;

/**
 * Parameters for listing custom endpoint provider configurations.
 *
 * @param providerName optional provider key filter
 */
public record ByokListCustomProviderConfigsParams(String providerName) {
}