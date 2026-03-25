(ns metabase.agora-embed.api
  "Public endpoints for Agora BI integration.

   Exposes unauthenticated endpoints for two embedding modes:

   1. Dashboard list (static embed): returns pre-signed JWT tokens for individual dashboards
      in the root collection that have `enable_embedding` set to true.

   2. Full collection page (full-app embed): signs a short-lived redirect token and
      exchanges it for an embedded session so the entire collection page renders inside
      an iframe without exposing the embedding secret key.

   Security model:
   - Tokens are signed with `embedding-secret-key` (MB_EMBEDDING_SECRET_KEY) — cannot be forged.
   - Full-app embed tokens expire in 5 minutes and are single-use via redirect.
   - The viewer user must be pre-created in Metabase and configured via MB_AGORA_VIEWER_EMAIL.
   - Endpoints require `enable-embedding-static` to be enabled globally.
   - Endpoints add CORS headers to allow the Agora origin to call them."
  (:require
   [buddy.sign.jwt :as jwt]
   [clojure.string :as str]
   [java-time.api :as t]
   [metabase.api.macros :as api.macros]
   [metabase.auth-identity.core :as auth-identity]
   [metabase.embedding.settings :as embedding.settings]
   [metabase.request.core :as request]
   [metabase.request.current :as request.current]
   [metabase.settings.core :refer [defsetting]]
   [metabase.util.i18n :refer [deferred-tru tru]]
   [metabase.util.malli.schema :as ms]
   [ring.util.response :as response]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defsetting agora-viewer-email
  (deferred-tru "Email of the Metabase user used as the read-only viewer for full-app collection embeds.
  Set MB_AGORA_VIEWER_EMAIL to the email of a pre-created, non-admin user that has view access
  to the collections you want to embed.")
  :visibility :internal
  :type       :string
  :default    nil)

(defn- wrap-cors
  "Ring middleware that adds CORS headers allowing any origin to call this endpoint.
   This is safe because the endpoint only returns pre-signed, read-only embed tokens
   for dashboards that an admin explicitly enabled for embedding."
  [handler]
  (fn [request respond raise]
    (if (= :options (:request-method request))
      (respond {:status  204
                :headers {"Access-Control-Allow-Origin"  "*"
                          "Access-Control-Allow-Methods" "GET, OPTIONS"
                          "Access-Control-Allow-Headers" "*"
                          "Access-Control-Max-Age"       "86400"}
                :body    nil})
      (handler
       request
       (fn [response]
         (respond (update response :headers merge
                          {"Access-Control-Allow-Origin"  "*"
                           "Access-Control-Allow-Headers" "*"
                           "Access-Control-Allow-Methods" "GET, OPTIONS"})))
       raise))))

(defn- sign-dashboard-token
  "Sign a JWT embed token for the given dashboard ID using the embedding secret key."
  [dashboard-id]
  (let [secret-key (embedding.settings/embedding-secret-key)]
    (when-not (seq secret-key)
      (throw (ex-info (tru "The embedding secret key has not been set.") {:status-code 503})))
    (jwt/sign {:resource {:dashboard dashboard-id}
               :params   {}}
              secret-key)))

(defn- embeddable-root-dashboards
  "Return all non-archived dashboards in the root collection (collection_id IS NULL)
   that have static embedding enabled."
  []
  (t2/select :model/Dashboard
             :collection_id nil
             :enable_embedding true
             :archived false
             {:order-by [[:name :asc]]}))

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :get "/embed/dashboards"
  "Return a list of all dashboards in the root collection that have static embedding
   enabled, each with a pre-signed JWT embed token.

   This endpoint is public (no authentication required) and is intended to be
   called by the Agora frontend to populate the BI dashboard navigation.

   Requires `enable-embedding-static` to be enabled in Metabase settings.

   Response shape:
   ```json
   {
     \"dashboards\": [
       {\"id\": 1, \"name\": \"Sales Overview\", \"description\": null, \"embed_url\": \"/embed/dashboard/<token>\"}
     ]
   }
   ```"
  [_route-params :- :map
   _query-params :- :map]
  (when-not (embedding.settings/enable-embedding-static)
    (throw (ex-info (tru "Static embedding is not enabled.") {:status-code 403})))
  (let [dashboards (embeddable-root-dashboards)]
    {:dashboards
     (for [{:keys [id name description]} dashboards]
       {:id          id
        :name        name
        :description description
        :embed_url   (str "/embed/dashboard/" (sign-dashboard-token id))})}))

;;; ──────────────────────────────────────────────────────────────────────────────
;;; Full collection-page embed  (uses MB_EMBEDDING_SECRET_KEY for signing)
;;; ──────────────────────────────────────────────────────────────────────────────

(defn- assert-embedding-enabled! []
  (when-not (embedding.settings/enable-embedding-static)
    (throw (ex-info (tru "Static embedding is not enabled.") {:status-code 403}))))

(defn- sign-collection-token
  "Sign a short-lived JWT (5-minute TTL) for a full-app collection embed redirect."
  [return-to]
  (let [secret-key (embedding.settings/embedding-secret-key)]
    (when-not (seq secret-key)
      (throw (ex-info (tru "The embedding secret key has not been set.") {:status-code 503})))
    (let [now-epoch (quot (System/currentTimeMillis) 1000)]
      (jwt/sign {:return-to return-to
                 :iat       now-epoch
                 :exp       (+ now-epoch 300)}
                secret-key))))

(defn- verify-collection-token
  "Verify a collection embed JWT signed with `embedding-secret-key` and return its claims."
  [token]
  (let [secret-key (embedding.settings/embedding-secret-key)]
    (when-not (seq secret-key)
      (throw (ex-info (tru "The embedding secret key has not been set.") {:status-code 503})))
    (try
      (jwt/unsign token secret-key {:leeway 60})
      (catch Throwable _
        (throw (ex-info (tru "Invalid or expired collection embed token.") {:status-code 401}))))))

(defn- relative-path?
  "Return true when `s` is a relative path (starts with `/` but not `//`)."
  [s]
  (and (string? s)
       (str/starts-with? s "/")
       (not (str/starts-with? s "//"))))

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :get "/embed/collection-url"
  "Return a signed, short-lived URL that an Agora iframe can navigate to in order to
   display the given Metabase collection page with a full-app embedded session.

   Query params:
   - `return_to` (required) – relative Metabase path, e.g. `/collection/5-agora`.

   Response:
   ```json
   {\"embed_url\": \"/api/agora/embed/collection-auth?token=<signed-jwt>\"}
   ```"
  [_route-params :- :map
   {:keys [return_to]} :- [:map [:return_to ms/NonBlankString]]]
  (assert-embedding-enabled!)
  (when-not (relative-path? return_to)
    (throw (ex-info (tru "return_to must be a relative path.") {:status-code 400})))
  {:embed_url (str "/api/agora/embed/collection-auth?token=" (sign-collection-token return_to))})

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :get "/embed/collection-auth"
  "Exchange a signed collection-embed token for a full-app embedded Metabase session.

   This endpoint is navigated to by the iframe. It verifies the JWT signed by
   `/embed/collection-url`, creates a `:full-app-embed` session for the configured
   viewer user (MB_AGORA_VIEWER_EMAIL), sets the embedded session cookie, and
   redirects to the path embedded in the token.

   The viewer user must exist in Metabase and have read access to the target collection."
  [_route-params :- :map
   {:keys [token]} :- [:map [:token ms/NonBlankString]]
   _body
   request]
  (assert-embedding-enabled!)
  (let [claims      (verify-collection-token token)
        return-to   (or (:return-to claims) "/")
        viewer-email (agora-viewer-email)]
    (when-not (seq viewer-email)
      (throw (ex-info (tru "MB_AGORA_VIEWER_EMAIL is not configured.") {:status-code 503})))
    (let [viewer-user (t2/select-one :model/User :email viewer-email :is_active true)]
      (when-not viewer-user
        (throw (ex-info (tru "Viewer user ''{0}'' not found or inactive." viewer-email)
                        {:status-code 503})))
      ;; Bind an embedded request context so the session is created as :full-app-embed
      ;; (anti-CSRF token is generated and the embedded session cookie is used).
      (let [embedded-request (assoc-in request [:headers "x-metabase-embedded"] "true")
            session          (request.current/with-current-request embedded-request
                               (auth-identity/create-session-with-auth-tracking!
                                viewer-user
                                (request/device-info request)
                                :provider/embed))]
        (request/set-session-cookies
         embedded-request
         (response/redirect return-to)
         session
         (t/zoned-date-time (t/zone-id "GMT")))))))

(def ^{:doc "Routes for `/api/agora`"
       :arglists '([request respond raise])} routes
  (api.macros/ns-handler *ns* wrap-cors))
