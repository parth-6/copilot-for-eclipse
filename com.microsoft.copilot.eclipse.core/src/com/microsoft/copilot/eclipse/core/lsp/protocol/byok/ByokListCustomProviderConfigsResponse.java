// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.lsp.protocol.byok;

import java.util.List;

/**
 * Response containing stored custom endpoint provider configuration metadata.
 *
 * @param providers custom endpoint providers
 */
public record ByokListCustomProviderConfigsResponse(List<ByokCustomProviderConfigInfo> providers) {
}