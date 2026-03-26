import { useSetting } from "metabase/common/hooks";
import { PLUGIN_METABOT } from "metabase/plugins";

import { useMetabotEnabledEmbeddingAware } from "./use-metabot-embedding-aware-enabled";

/**
 * Returns whether LLM SQL generation is available and enabled.
 *
 * Enterprise path: metabot_v3 feature is enabled AND metabot is enabled (embedding-aware)
 * OSS path: not hosted AND (Anthropic or OpenAI API key configured for BYOK SQL gen)
 */
export const useLlmSqlGenerationEnabled = (): boolean => {
  const isMetabotEnabled = useMetabotEnabledEmbeddingAware({
    requireMeteabotFeature: false,
  });
  const isHosted = useSetting("is-hosted?");
  const isAnthropicConfigured = useSetting("llm-anthropic-api-key-configured?");
  const isOpenaiConfigured = useSetting("llm-openai-api-key-configured?");

  // Enterprise: metabot_v3 feature + metabot enabled
  if (PLUGIN_METABOT.hasFeature) {
    return isMetabotEnabled;
  }

  // OSS: not hosted + Anthropic or OpenAI API key configured
  return !isHosted && (!!isAnthropicConfigured || !!isOpenaiConfigured);
};
