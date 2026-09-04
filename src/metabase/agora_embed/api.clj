(ns metabase.agora-embed.api
  "Public endpoints for Agora BI integration.

   Exposes unauthenticated endpoints for two embedding modes:

   1. Dashboard list (static embed): returns pre-signed JWT tokens for individual dashboards
      that have `enable_embedding` set to true.

   2. Full collection page (full-app embed): exchanges a token for an embedded session so the
      entire collection page renders inside an iframe without exposing the embedding secret key.

   Each endpoint works in one of two modes:

   - **Multi-tenant mode** (`jwt` query parameter present). Agora signs a JWT with the JWT SSO shared
     secret carrying the user's `email`, `first_name`, `last_name` and the tenant claim (`@tenant`).
     The token is verified by the JWT SSO provider; the tenant and the user are provisioned on first
     login; the session belongs to a tenant user who only sees their tenant collection, the shared
     tenant collections and the databases granted to their tenant groups. The dashboard list is
     scoped to the tenant collection tree. Requires the Enterprise edition, the Tenants feature and
     the multi-tenant user strategy (`use-tenants`).

   - **Legacy single-viewer mode** (no `jwt`). Kept for backwards compatibility: lists embeddable
     dashboards in the root collection and creates sessions for the user configured through
     MB_AGORA_VIEWER_EMAIL. Do not rely on it for multi-tenant deployments.

   Security model:
   - Legacy tokens are signed with `embedding-secret-key` (MB_EMBEDDING_SECRET_KEY) and cannot be forged.
   - Multi-tenant tokens are signed by Agora with `jwt-shared-secret` and expire after 3 minutes.
   - Endpoints require `enable-embedding-static` to be enabled globally.
   - Endpoints add CORS headers to allow the Agora origin to call them."
  (:require
   [buddy.sign.jwt :as jwt]
   [clojure.string :as str]
   [java-time.api :as t]
   [metabase.api.macros :as api.macros]
   [metabase.auth-identity.core :as auth-identity]
   [metabase.embedding.settings :as embedding.settings]
   [metabase.premium-features.core :refer [defenterprise]]
   [metabase.request.core :as request]
   [metabase.util.i18n :refer [deferred-tru]]
   [metabase.util.malli.schema :as ms]
   [ring.util.response :as response]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn- agora-viewer-email
  "Return the value of MB_AGORA_VIEWER_EMAIL. This is intentionally a plain env-var
   read instead of `defsetting` to avoid AOT compilation issues with the i18n
   system (`str*` is bound to the throwing sentinel during `*compile-files*`)."
  []
  (System/getenv "MB_AGORA_VIEWER_EMAIL"))

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

;;; ──────────────────────────────────────────────────────────────────────────────
;;; Enterprise hooks (multi-tenant mode)
;;; ──────────────────────────────────────────────────────────────────────────────

(defn- ee-required-error []
  (ex-info (str (deferred-tru "Multi-tenant Agora embedding requires the Enterprise edition with the Tenants feature."))
           {:status-code 503}))

(defenterprise verify-tenant-jwt
  "Verify an Agora tenant JWT and return the tenant it refers to, or `nil` if the tenant has not been provisioned
   yet. OSS version: multi-tenant embedding is not available."
  metabase-enterprise.agora-embed.core
  [_jwt]
  (throw (ee-required-error)))

(defenterprise tenant-dashboards
  "Return the dashboards in the tenant collection tree of the tenant named in an Agora JWT. OSS version:
   multi-tenant embedding is not available."
  metabase-enterprise.agora-embed.core
  [_jwt]
  (throw (ee-required-error)))

(defenterprise tenant-login!
  "Log in the tenant user described by an Agora JWT and return `{:session ... :user ... :tenant ...}`. OSS version:
   multi-tenant embedding is not available."
  metabase-enterprise.agora-embed.core
  [_jwt _embedded-request]
  (throw (ee-required-error)))

;;; ──────────────────────────────────────────────────────────────────────────────
;;; Dashboard list  (static embed tokens)
;;; ──────────────────────────────────────────────────────────────────────────────

(defn- assert-embedding-enabled! []
  (when-not (embedding.settings/enable-embedding-static)
    (throw (ex-info (str (deferred-tru "Static embedding is not enabled.")) {:status-code 403}))))

(defn- embedding-secret-key!
  "Return the embedding secret key, or throw a 503 if it has not been configured."
  []
  (let [secret-key (embedding.settings/embedding-secret-key)]
    (when-not (seq secret-key)
      (throw (ex-info (str (deferred-tru "The embedding secret key has not been set.")) {:status-code 503})))
    secret-key))

(defn- sign-dashboard-token
  "Sign a JWT embed token for the given dashboard ID using the embedding secret key."
  [dashboard-id]
  (jwt/sign {:resource {:dashboard dashboard-id}
             :params   {}}
            (embedding-secret-key!)))

(defn- embeddable-root-dashboards
  "Return all non-archived dashboards in the root collection (collection_id IS NULL)
   that have static embedding enabled."
  []
  (t2/select :model/Dashboard
             :collection_id nil
             :enable_embedding true
             :archived false
             {:order-by [[:name :asc]]}))

(defn- present-dashboard
  "Shape a dashboard for the Agora navigation. `embed_url` (static embed) is only present when the dashboard has
   static embedding enabled; `app_url` always points at the dashboard inside a full-app embedded session."
  [{:keys [id name description collection_id enable_embedding]}]
  {:id            id
   :name          name
   :description   description
   :collection_id collection_id
   :app_url       (str "/dashboard/" id)
   :embed_url     (when enable_embedding
                    (str "/embed/dashboard/" (sign-dashboard-token id)))})

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :get "/embed/dashboards"
  "Return the dashboards Agora should show in its BI navigation.

   This endpoint is public (no authentication required) and is intended to be
   called by the Agora frontend to populate the BI dashboard navigation.

   Query params:
   - `jwt` (optional): Agora tenant token signed with the JWT SSO shared secret. When present the
     response is scoped to the tenant collection tree of the tenant named in the token (multi-tenant
     mode). Without it, the legacy behaviour applies: dashboards in the root collection that have
     static embedding enabled.

   Requires `enable-embedding-static` to be enabled in Metabase settings.

   Response shape:
   ```json
   {
     \"dashboards\": [
       {\"id\": 1, \"name\": \"Sales Overview\", \"description\": null, \"collection_id\": 7,
        \"app_url\": \"/dashboard/1\", \"embed_url\": \"/embed/dashboard/<token>\"}
     ]
   }
   ```"
  [_route-params :- :map
   {:keys [jwt]} :- [:map [:jwt {:optional true} ms/NonBlankString]]]
  (assert-embedding-enabled!)
  (let [dashboards (if jwt
                     (tenant-dashboards jwt)
                     (embeddable-root-dashboards))]
    {:dashboards (mapv present-dashboard dashboards)}))

;;; ──────────────────────────────────────────────────────────────────────────────
;;; Full collection-page embed
;;; ──────────────────────────────────────────────────────────────────────────────

(defn- sign-collection-token
  "Sign a short-lived JWT (5-minute TTL) for a legacy full-app collection embed redirect."
  [return-to]
  (let [now-epoch (quot (System/currentTimeMillis) 1000)]
    (jwt/sign {:return-to return-to
               :iat       now-epoch
               :exp       (+ now-epoch 300)}
              (embedding-secret-key!))))

(defn- verify-collection-token
  "Verify a legacy collection embed JWT signed with `embedding-secret-key` and return its claims."
  [token]
  (let [secret-key (embedding-secret-key!)]
    (try
      (jwt/unsign token secret-key {:leeway 60})
      (catch Throwable _
        (throw (ex-info (str (deferred-tru "Invalid or expired collection embed token.")) {:status-code 401}))))))

(defn- relative-path?
  "Return true when `s` is a relative path (starts with `/` but not `//`)."
  [s]
  (and (string? s)
       (str/starts-with? s "/")
       (not (str/starts-with? s "//"))))

(defn- assert-relative-path! [return-to]
  (when-not (relative-path? return-to)
    (throw (ex-info (str (deferred-tru "return_to must be a relative path.")) {:status-code 400}))))

(defn- append-query-param
  "Append a query param to `path` preserving existing query params."
  [path param-name param-value]
  (str path
       (if (str/includes? path "?") "&" "?")
       param-name
       "="
       (java.net.URLEncoder/encode (str param-value) "UTF-8")))

(defn- tenant-collection-path [tenant]
  (str "/collection/" (:collection_id tenant)))

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :get "/embed/collection-url"
  "Return a URL that an Agora iframe can navigate to in order to display a Metabase collection page
   with a full-app embedded session.

   Query params:
   - `jwt` (optional): Agora tenant token. Multi-tenant mode: the token is verified and the returned
     URL logs the tenant user in. `return_to` is then optional and defaults to the tenant collection.
   - `return_to`: relative Metabase path, e.g. `/collection/5-agora`. Required in legacy mode.

   Response:
   ```json
   {\"embed_url\": \"/api/agora/embed/collection-auth?jwt=<token>&return_to=...\",
    \"tenant\": {\"id\": 1, \"slug\": \"acme\", \"name\": \"acme\", \"collection_id\": 12}}
   ```
   `tenant` is only present in multi-tenant mode and is `null` until the tenant's first login."
  [_route-params :- :map
   {:keys [jwt return_to]} :- [:map
                               [:jwt {:optional true} ms/NonBlankString]
                               [:return_to {:optional true} ms/NonBlankString]]]
  (assert-embedding-enabled!)
  (when return_to
    (assert-relative-path! return_to))
  (if jwt
    (let [tenant (verify-tenant-jwt jwt)]
      {:embed_url (cond-> (append-query-param "/api/agora/embed/collection-auth" "jwt" jwt)
                    return_to (append-query-param "return_to" return_to))
       :tenant    tenant})
    (do
      (when-not return_to
        (throw (ex-info (str (deferred-tru "return_to is required.")) {:status-code 400})))
      {:embed_url (str "/api/agora/embed/collection-auth?token=" (sign-collection-token return_to))})))

(defn- embedded-request
  "Mark `request` as coming from an embedded frontend so that the session is created as `:full-app-embed`
   (anti-CSRF token is generated and the embedded session cookie is used)."
  [request]
  (assoc-in request [:headers "x-metabase-embedded"] "true"))

(defn- session-redirect
  "Build the redirect response for a freshly created embedded `session`, setting the session cookies and
   appending the anti-CSRF token to `return-to` when there is one."
  [embedded-request session return-to]
  (let [redirect-target (if (seq (:anti_csrf_token session))
                          (append-query-param return-to "mb_anti_csrf_token" (:anti_csrf_token session))
                          return-to)]
    (request/set-session-cookies
     embedded-request
     (response/redirect redirect-target)
     session
     (t/zoned-date-time (t/zone-id "GMT")))))

(defn- legacy-viewer-session!
  "Create a full-app-embed session for the single viewer user configured through MB_AGORA_VIEWER_EMAIL."
  [embedded-request]
  (let [viewer-email (agora-viewer-email)]
    (when-not (seq viewer-email)
      (throw (ex-info (str (deferred-tru "MB_AGORA_VIEWER_EMAIL is not configured.")) {:status-code 503})))
    (let [viewer-user (t2/select-one :model/User :email viewer-email :is_active true)]
      (when-not viewer-user
        (throw (ex-info (str (deferred-tru "Viewer user ''{0}'' not found or inactive." viewer-email))
                        {:status-code 503})))
      (request/with-current-request embedded-request
        (auth-identity/create-session-with-auth-tracking!
         viewer-user
         (request/device-info embedded-request)
         :provider/embed)))))

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :get "/embed/collection-auth"
  "Exchange a token for a full-app embedded Metabase session and redirect into the app.

   This endpoint is navigated to by the iframe. Exactly one of `jwt` or `token` must be given:

   - `jwt`: Agora tenant token (multi-tenant mode). The JWT SSO provider verifies it, provisions the
     tenant and the user if needed, and the session belongs to that tenant user. `return_to` defaults to
     the tenant collection.
   - `token`: legacy token issued by `/embed/collection-url`. Creates a session for the configured viewer
     user (MB_AGORA_VIEWER_EMAIL) and redirects to the path embedded in the token."
  [_route-params :- :map
   {:keys [jwt token return_to]} :- [:map
                                     [:jwt {:optional true} ms/NonBlankString]
                                     [:token {:optional true} ms/NonBlankString]
                                     [:return_to {:optional true} ms/NonBlankString]]
   _body
   request]
  (assert-embedding-enabled!)
  (when (= (boolean jwt) (boolean token))
    (throw (ex-info (str (deferred-tru "Exactly one of jwt or token is required.")) {:status-code 400})))
  (let [embedded-request (embedded-request request)]
    (if jwt
      (let [_                        (when return_to (assert-relative-path! return_to))
            {:keys [session tenant]} (tenant-login! jwt embedded-request)]
        (session-redirect embedded-request session (or return_to (tenant-collection-path tenant))))
      (let [claims    (verify-collection-token token)
            return-to (or (:return-to claims) "/")
            session   (legacy-viewer-session! embedded-request)]
        (session-redirect embedded-request session return-to)))))

(def ^{:doc "Routes for `/api/agora`"
       :arglists '([request respond raise])} routes
  (api.macros/ns-handler *ns* wrap-cors))
