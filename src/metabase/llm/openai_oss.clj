(ns metabase.llm.openai-oss
  "OpenAI API client for OSS BYOK SQL generation via Chat Completions + tool calls."
  (:require
   [clj-http.client :as http]
   [clojure.core.memoize :as memoize]
   [clojure.string :as str]
   [metabase.llm.settings :as llm.settings]
   [metabase.util :as u]
   [metabase.util.json :as json])
  (:import
   (com.fasterxml.jackson.core JsonParseException)))

(set! *warn-on-reflection* true)

(def ^:private generate-sql-tool
  "OpenAI function tool matching Anthropic tool shape for downstream parsing."
  {:type     "function"
   :function {:name         "generate_sql"
              :description  "Generate SQL query from the user's request. Always use this tool to return your response."
              :parameters   {:type       "object"
                             :properties {:sql         {:type        "string"
                                                        :description "The generated SQL query"}
                                          :explanation {:type        "string"
                                                        :description "Brief explanation of the query"}}
                             :required   ["sql"]}}})

(defn- build-request-headers
  [api-key]
  {"authorization"  (str "Bearer " api-key)
   "content-type"   "application/json"})

(defn- build-messages
  [{:keys [system messages]}]
  (cond-> []
    (not (str/blank? system))
    (conj {:role "system" :content system})

    (seq messages)
    (into messages)))

(defn- build-request-body
  [{:keys [model system messages]}]
  {:model            model
   :messages         (build-messages {:system system :messages messages})
   :tools            [generate-sql-tool]
   :tool_choice      {:type "function" :function {:name "generate_sql"}}
   :max_tokens       (llm.settings/llm-max-tokens)})

(defn- extract-tool-arguments
  "Parse first generate_sql tool_call arguments from chat completion response."
  [response-body]
  (let [choice   (first (:choices response-body))
        message  (:message choice)
        calls    (:tool_calls message)
        call     (first calls)
        fn-block (:function call)
        args-str (:arguments fn-block)]
    (when (and (= "generate_sql" (:name fn-block))
               (not (str/blank? args-str)))
      (try
        (json/decode+kw args-str)
        (catch JsonParseException _
          nil)))))

(defn- handle-api-error
  [exception]
  (if-let [response-body (some-> exception ex-data :body)]
    (let [parsed (try
                   (json/decode response-body)
                   (catch JsonParseException _
                     {:error {:message response-body}}))]
      (throw (ex-info (or (-> parsed :error :message)
                          "OpenAI API request failed")
                      {:type   :openai-api-error
                       :status (some-> exception ex-data :status)
                       :body   parsed}
                      exception)))
    (throw exception)))

(defn- get-api-key-or-throw
  []
  (let [api-key (llm.settings/llm-openai-api-key)]
    (when (str/blank? api-key)
      (throw (ex-info "LLM is not configured. Please set an OpenAI API key via MB_LLM_OPENAI_API_KEY."
                      {:type :llm-not-configured})))
    api-key))

(defn- chat-completion-model-id?
  "Heuristic filter for models suitable for chat completions + tools."
  [id]
  (let [l (str/lower-case id)]
    (and (not (str/includes? l "embedding"))
         (not (str/includes? l "embed"))
         (not (str/includes? l "whisper"))
         (not (str/includes? l "tts"))
         (not (str/includes? l "dall-e"))
         (not (str/includes? l "moderation"))
         (not (str/includes? l "realtime"))
         (or (str/starts-with? l "gpt-")
             (re-find #"^o[0-9]" l)
             (str/starts-with? l "chatgpt-")))))

(def ^:private list-models*
  (memoize/ttl
   (fn [api-key]
     (try
       (let [url      (str (llm.settings/llm-openai-api-base-url) "/v1/models")
             response (http/get url
                                  {:headers          (build-request-headers api-key)
                                   :as               :json
                                   :socket-timeout   (llm.settings/llm-request-timeout-ms)
                                   :connection-timeout (llm.settings/llm-connection-timeout-ms)})
             body     (:body response)
             models   (->> (:data body)
                           (filter (comp chat-completion-model-id? :id))
                           (sort-by :id)
                           reverse)]
         {:models (mapv (fn [m]
                          (let [id (:id m)]
                            {:id id :display_name id}))
                        models)})
       (catch Exception e
         (handle-api-error e))))
   :ttl/threshold (* 5 60 1000)))

(defn list-models
  "List OpenAI models (cached 5 minutes)."
  []
  (list-models* (get-api-key-or-throw)))

(defn chat-completion
  "Chat completion with forced generate_sql tool. Return shape matches [[metabase.llm.anthropic/chat-completion]]."
  [{:keys [model system messages]}]
  (let [model       (or model (llm.settings/llm-openai-model))
        request     {:model model :system system :messages messages}
        start-time  (u/start-timer)]
    (try
      (let [url         (str (llm.settings/llm-openai-api-base-url) "/v1/chat/completions")
            response    (http/post url
                                     {:headers            (build-request-headers (get-api-key-or-throw))
                                      :body               (json/encode (build-request-body request))
                                      :as                 :json
                                      :content-type       :json
                                      :socket-timeout     (llm.settings/llm-request-timeout-ms)
                                      :connection-timeout (llm.settings/llm-connection-timeout-ms)})
            duration-ms (u/since-ms start-time)
            body        (:body response)
            usage       (:usage body)]
        {:result      (extract-tool-arguments body)
         :duration-ms duration-ms
         :usage       {:model      model
                       :prompt     (:prompt_tokens usage)
                       :completion (:completion_tokens usage)}})
      (catch Exception e
        (handle-api-error e)))))
