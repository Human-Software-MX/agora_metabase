import { type FocusEvent, useMemo, useState } from "react";
import { t } from "ttag";

import {
  SettingsPageWrapper,
  SettingsSection,
} from "metabase/admin/components/SettingsSection";
import { SetByEnvVar } from "metabase/admin/settings/components/widgets/AdminSettingInput";
import { useListModelsQuery } from "metabase/api/llm";
import { getErrorMessage } from "metabase/api/utils/errors";
import { useAdminSetting } from "metabase/api/utils/settings";
import { LoadingAndErrorWrapper } from "metabase/common/components/LoadingAndErrorWrapper";
import { useSetting, useToast } from "metabase/common/hooks";
import {
  Alert,
  Box,
  PasswordInput,
  Select,
  Stack,
  TextInput,
} from "metabase/ui";

export function MetabotSQLGenerationSettingsSection() {
  const anthropicApiKey = useAdminSetting("llm-anthropic-api-key");
  const anthropicModel = useAdminSetting("llm-anthropic-model");
  const { updateSetting, settingDetails: anthropicApiKeyDetails } =
    anthropicApiKey;
  const { settingDetails: anthropicModelDetails } = anthropicModel;

  const openaiApiKey = useAdminSetting("llm-openai-api-key");
  const openaiModel = useAdminSetting("llm-openai-model");
  const {
    updateSetting: updateOpenaiSetting,
    settingDetails: openaiApiKeyDetails,
  } = openaiApiKey;
  const { settingDetails: openaiModelDetails } = openaiModel;

  const [localAnthropicApiKey, setLocalAnthropicApiKey] = useState<
    string | null
  >(null);
  const [anthropicApiKeyError, setAnthropicApiKeyError] = useState<
    string | null
  >(null);
  const [isAnthropicApiKeyVisible, setIsAnthropicApiKeyVisible] =
    useState(false);

  const [localOpenaiApiKey, setLocalOpenaiApiKey] = useState<string | null>(
    null,
  );
  const [openaiApiKeyError, setOpenaiApiKeyError] = useState<string | null>(
    null,
  );
  const [isOpenaiApiKeyVisible, setIsOpenaiApiKeyVisible] = useState(false);

  const [sendToast] = useToast();

  const isAnthropicApiKeyEnvVar = !!(
    anthropicApiKeyDetails?.is_env_setting && anthropicApiKeyDetails?.env_name
  );
  const isAnthropicModelEnvVar = !!(
    anthropicModelDetails?.is_env_setting && anthropicModelDetails?.env_name
  );

  const isOpenaiApiKeyEnvVar = !!(
    openaiApiKeyDetails?.is_env_setting && openaiApiKeyDetails?.env_name
  );
  const isOpenaiModelEnvVar = !!(
    openaiModelDetails?.is_env_setting && openaiModelDetails?.env_name
  );

  const isAnthropicConfigured = useSetting("llm-anthropic-api-key-configured?");
  const isOpenaiConfigured = useSetting("llm-openai-api-key-configured?");

  const savedAnthropicApiKey = anthropicApiKey.value ?? "";
  const hasAnthropicKey =
    isAnthropicConfigured || savedAnthropicApiKey.trim().length > 0;
  const anthropicApiKeyDisplayValue =
    localAnthropicApiKey ?? savedAnthropicApiKey;
  const anthropicApiKeyPlaceholder =
    isAnthropicConfigured && !localAnthropicApiKey
      ? "•".repeat(108)
      : t`Enter your API key`;
  const AnthropicApiKeyInput = isAnthropicApiKeyEnvVar
    ? TextInput
    : PasswordInput;

  const savedOpenaiApiKey = openaiApiKey.value ?? "";
  const hasOpenAiKey =
    isOpenaiConfigured || savedOpenaiApiKey.trim().length > 0;
  const openaiApiKeyDisplayValue = localOpenaiApiKey ?? savedOpenaiApiKey;
  const openaiApiKeyPlaceholder =
    isOpenaiConfigured && !localOpenaiApiKey
      ? "•".repeat(108)
      : t`Enter your API key`;
  const OpenaiApiKeyInput = isOpenaiApiKeyEnvVar ? TextInput : PasswordInput;

  const {
    data: anthropicModelsData,
    isLoading: isAnthropicModelsLoading,
    error: anthropicModelsError,
  } = useListModelsQuery(
    { clientCacheKey: "anthropic" },
    { skip: !hasAnthropicKey },
  );

  const fetchOpenaiModels = hasOpenAiKey && !isAnthropicConfigured;
  const {
    data: openaiModelsData,
    isLoading: isOpenaiModelsLoading,
    error: openaiModelsError,
  } = useListModelsQuery(
    { clientCacheKey: "openai" },
    { skip: !fetchOpenaiModels },
  );

  const anthropicModelOptions = useMemo(() => {
    return (anthropicModelsData?.models || []).map((m) => ({
      value: m.id,
      label: m.display_name,
    }));
  }, [anthropicModelsData]);

  const openaiModelOptions = useMemo(() => {
    return (openaiModelsData?.models || []).map((m) => ({
      value: m.id,
      label: m.display_name,
    }));
  }, [openaiModelsData]);

  const isSettingsLoading =
    anthropicApiKey.isLoading ||
    anthropicModel.isLoading ||
    openaiApiKey.isLoading ||
    openaiModel.isLoading;
  const settingsError =
    anthropicApiKey.error ||
    anthropicModel.error ||
    openaiApiKey.error ||
    openaiModel.error;

  if (isSettingsLoading || settingsError) {
    return (
      <LoadingAndErrorWrapper
        loading={isSettingsLoading}
        error={settingsError}
      />
    );
  }

  const anthropicModelValue = anthropicModel.value ?? "";
  const isAnthropicModelFieldDisabled =
    !hasAnthropicKey || isAnthropicModelsLoading || !!anthropicModelsError;
  const isAnthropicDeprecatedModel =
    anthropicModelOptions.length > 0 &&
    !anthropicModelOptions.some((opt) => opt.value === anthropicModelValue);

  const openaiModelValue = openaiModel.value ?? "";
  const isOpenaiModelFieldDisabled =
    !fetchOpenaiModels || isOpenaiModelsLoading || !!openaiModelsError;
  const isOpenaiDeprecatedModel =
    openaiModelOptions.length > 0 &&
    !openaiModelOptions.some((opt) => opt.value === openaiModelValue);

  const handleAnthropicApiKeyBlur = async (e: FocusEvent<HTMLInputElement>) => {
    const value = e.target.value;
    if (value !== savedAnthropicApiKey) {
      setAnthropicApiKeyError(null);
      const response = await updateSetting({
        key: "llm-anthropic-api-key",
        value,
        toast: false,
      });
      if (response.error) {
        setAnthropicApiKeyError(
          getErrorMessage(response.error, t`Failed to save API key`),
        );
        return;
      }
      sendToast({ message: t`API key saved successfully!`, icon: "check" });
      setIsAnthropicApiKeyVisible(false);
    }
    setLocalAnthropicApiKey(null);
  };

  const handleOpenaiApiKeyBlur = async (e: FocusEvent<HTMLInputElement>) => {
    const value = e.target.value;
    if (value !== savedOpenaiApiKey) {
      setOpenaiApiKeyError(null);
      const response = await updateOpenaiSetting({
        key: "llm-openai-api-key",
        value,
        toast: false,
      });
      if (response.error) {
        setOpenaiApiKeyError(
          getErrorMessage(response.error, t`Failed to save API key`),
        );
        return;
      }
      sendToast({ message: t`API key saved successfully!`, icon: "check" });
      setIsOpenaiApiKeyVisible(false);
    }
    setLocalOpenaiApiKey(null);
  };

  const handleAnthropicModelChange = (value: string | null) => {
    if (value && value !== anthropicModel.value) {
      updateSetting({ key: "llm-anthropic-model", value });
    }
  };

  const handleOpenaiModelChange = (value: string | null) => {
    if (value && value !== openaiModel.value) {
      updateOpenaiSetting({ key: "llm-openai-model", value });
    }
  };

  return (
    <SettingsPageWrapper>
      <SettingsSection
        title={t`Connect to a model (Anthropic)`}
        description={t`Use an Anthropic API key for OSS SQL generation. When an Anthropic key is present, it takes priority over OpenAI.`}
      >
        <Stack gap="md">
          <Box>
            <AnthropicApiKeyInput
              disabled={isAnthropicApiKeyEnvVar}
              label={t`Anthropic API Key`}
              placeholder={anthropicApiKeyPlaceholder}
              value={anthropicApiKeyDisplayValue}
              onChange={(e) => {
                setLocalAnthropicApiKey(e.target.value);
                setAnthropicApiKeyError(null);
                setIsAnthropicApiKeyVisible(true);
              }}
              onBlur={handleAnthropicApiKeyBlur}
              error={anthropicApiKeyError}
              {...(isAnthropicApiKeyEnvVar
                ? {}
                : {
                    visible: isAnthropicApiKeyVisible,
                    onVisibilityChange: setIsAnthropicApiKeyVisible,
                  })}
            />
            {isAnthropicApiKeyEnvVar && (
              <SetByEnvVar varName={anthropicApiKeyDetails.env_name!} />
            )}
          </Box>

          <Box>
            <Select
              disabled={
                isAnthropicModelFieldDisabled || !!isAnthropicModelEnvVar
              }
              label={t`Model`}
              placeholder={
                isAnthropicModelsLoading ? t`Loading models...` : undefined
              }
              data={anthropicModelOptions}
              value={isAnthropicDeprecatedModel ? null : anthropicModelValue}
              onChange={handleAnthropicModelChange}
              error={
                anthropicModelsError
                  ? t`Failed to load models`
                  : isAnthropicDeprecatedModel
                    ? t`The model "${anthropicModelValue}" is no longer available. Please select a new model.`
                    : undefined
              }
            />
            {isAnthropicModelEnvVar && (
              <SetByEnvVar varName={anthropicModelDetails.env_name!} />
            )}
          </Box>
        </Stack>
      </SettingsSection>

      <SettingsSection
        title={t`Connect to a model (OpenAI)`}
        description={t`Alternatively, use an OpenAI API key. OpenAI is used only when no Anthropic API key is configured.`}
      >
        <Stack gap="md">
          {isAnthropicConfigured && hasOpenAiKey ? (
            <Alert>
              {t`SQL generation currently uses Anthropic because an Anthropic API key is configured. Remove the Anthropic key to use OpenAI.`}
            </Alert>
          ) : null}
          <Box>
            <OpenaiApiKeyInput
              disabled={isOpenaiApiKeyEnvVar}
              label={t`OpenAI API Key`}
              placeholder={openaiApiKeyPlaceholder}
              value={openaiApiKeyDisplayValue}
              onChange={(e) => {
                setLocalOpenaiApiKey(e.target.value);
                setOpenaiApiKeyError(null);
                setIsOpenaiApiKeyVisible(true);
              }}
              onBlur={handleOpenaiApiKeyBlur}
              error={openaiApiKeyError}
              {...(isOpenaiApiKeyEnvVar
                ? {}
                : {
                    visible: isOpenaiApiKeyVisible,
                    onVisibilityChange: setIsOpenaiApiKeyVisible,
                  })}
            />
            {isOpenaiApiKeyEnvVar && (
              <SetByEnvVar varName={openaiApiKeyDetails.env_name!} />
            )}
          </Box>

          <Box>
            <Select
              disabled={isOpenaiModelFieldDisabled || !!isOpenaiModelEnvVar}
              label={t`Model`}
              placeholder={
                isOpenaiModelsLoading ? t`Loading models...` : undefined
              }
              data={openaiModelOptions}
              value={isOpenaiDeprecatedModel ? null : openaiModelValue}
              onChange={handleOpenaiModelChange}
              error={
                openaiModelsError
                  ? t`Failed to load models`
                  : isOpenaiDeprecatedModel
                    ? t`The model "${openaiModelValue}" is no longer available. Please select a new model.`
                    : undefined
              }
            />
            {isOpenaiModelEnvVar && (
              <SetByEnvVar varName={openaiModelDetails.env_name!} />
            )}
          </Box>
        </Stack>
      </SettingsSection>
    </SettingsPageWrapper>
  );
}
