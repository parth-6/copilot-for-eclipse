// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.completion;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.microsoft.copilot.eclipse.core.Constants;
import com.microsoft.copilot.eclipse.core.CopilotCore;

/**
 * Shared preferences for using a custom BYOK model for inline completions.
 */
public final class ByokInlineCompletionSettings {

  private ByokInlineCompletionSettings() {
  }

  private static Preferences getPreferences() {
    return InstanceScope.INSTANCE.getNode(CopilotCore.getPlugin().getBundle().getSymbolicName());
  }

  public static boolean isEnabled() {
    return getPreferences().getBoolean(Constants.BYOK_INLINE_COMPLETION_ENABLED, false);
  }

  public static String getProviderName() {
    return getPreferences().get(Constants.BYOK_INLINE_COMPLETION_PROVIDER, "");
  }

  public static String getModelId() {
    return getPreferences().get(Constants.BYOK_INLINE_COMPLETION_MODEL, "");
  }

  /**
   * Persist whether BYOK completions are enabled and the selected custom model.
   */
  public static void setSelection(boolean enabled, String providerName, String modelId) {
    Preferences preferences = getPreferences();
    preferences.putBoolean(Constants.BYOK_INLINE_COMPLETION_ENABLED, enabled);
    preferences.put(Constants.BYOK_INLINE_COMPLETION_PROVIDER, providerName == null ? "" : providerName);
    preferences.put(Constants.BYOK_INLINE_COMPLETION_MODEL, modelId == null ? "" : modelId);
    try {
      preferences.flush();
    } catch (BackingStoreException e) {
      CopilotCore.LOGGER.error("Failed to save BYOK inline completion preferences", e);
    }
  }
}