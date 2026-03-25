(ns metabase.metabot.task.suggested-prompts-generator
  "Job to execute on start of an instance"
  (:require
   [clojure.string :as str]
   [clojurewerkz.quartzite.jobs :as jobs]
   [clojurewerkz.quartzite.triggers :as triggers]
   [metabase.llm.settings :as llm.settings]
   [metabase.metabot.config :as metabot.config]
   [metabase.metabot.settings :as metabot.settings]
   [metabase.metabot.suggested-prompts :as metabot.suggested-prompts]
   [metabase.request.core :as request]
   [metabase.task.core :as task]
   [metabase.util.log :as log]
   [toucan2.core :as t2])
  (:import
   (java.time Instant)
   (java.util Date)
   (org.quartz DisallowConcurrentExecution)))

(set! *warn-on-reflection* true)

(def ^:private generator-job-key (jobs/key "metabase.task.metabot.suggested-prompts-generator.job"))
(def ^:private generator-trigger-key (triggers/key "metabase.task.metabot.suggested-prompts-generator.trigger"))

(defn- non-blank-api-key?
  [s]
  (boolean (and (some? s) (not (str/blank? (str s))))))

(defn- native-llm-key-present-for-metabot?
  "True when the API key for [[metabase.metabot.settings/llm-metabot-provider]]'s provider prefix is set."
  []
  (let [provider (first (str/split (metabot.settings/llm-metabot-provider) #"/" 2))]
    (case provider
      "openrouter" (non-blank-api-key? (llm.settings/llm-openrouter-api-key))
      "anthropic"  (non-blank-api-key? (llm.settings/llm-anthropic-api-key))
      "openai"     (non-blank-api-key? (llm.settings/llm-openai-api-key))
      false)))

(defn- maybe-generate-suggested-prompts! []
  (try
    ;; Run as admin since this is a system task generating prompts for all content in scope.
    ;; Users will only see prompts for content they have access to (filtered at query time).
    (request/as-admin
      (let [metabot-eid (get-in metabot.config/metabot-config
                                [metabot.config/internal-metabot-id :entity-id])
            metabot-id  (t2/select-one-pk :model/Metabot :entity_id metabot-eid)
            suggested-prompts-cnt (t2/count :model/MetabotPrompt :metabot_id metabot-id)]
        (cond
          (and (metabot.settings/use-native-agent)
               (not (native-llm-key-present-for-metabot?)))
          (log/info
           (str "Skipping Metabot suggested prompt generation: native agent is enabled but no API key is set "
                "for the configured Metabot LLM provider (see MB_LLM_OPENROUTER_API_KEY, "
                "MB_LLM_ANTHROPIC_API_KEY, or MB_LLM_OPENAI_API_KEY, or Admin → Settings)."))

          (zero? suggested-prompts-cnt)
          (do
            (log/info "No suggested prompts found. Generating suggested prompts.")
            (metabot.suggested-prompts/generate-sample-prompts metabot-id)
            (log/info "Suggested prompts generated successfully."))

          :else
          (log/info "Suggested prompts are present. Not generating."))))
    (catch Exception e
      (log/errorf "Suggested prompts generation failed: %s" (.getMessage e)))))

(task/defjob ^{DisallowConcurrentExecution true
               :doc "Initial _suggested prompts_ generation for internal Metabot."}
  SuggestedPromptsGenerator [_ctx]
  (maybe-generate-suggested-prompts!))

(defmethod task/init! ::SuggestedPromptsGenerator
  [_]
  (let [job     (jobs/build
                 (jobs/of-type SuggestedPromptsGenerator)
                 (jobs/with-identity generator-job-key))
        trigger (triggers/build
                 (triggers/with-identity generator-trigger-key)
                  ;; Start the job a moment after startup.
                 (triggers/start-at (Date/from (.plusSeconds (Instant/now) 10))))]
    (task/schedule-task! job trigger)))
