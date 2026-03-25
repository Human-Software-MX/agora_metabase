(ns metabase.agora-embed.api
  "Public endpoint for Agora BI integration.

   Exposes a single unauthenticated endpoint that returns pre-signed static embed
   tokens for all dashboards in the root collection that have `enable_embedding`
   set to true. This allows the Agora frontend to render a navigation list of
   dashboards inside an iframe without exposing the embedding secret key.

   Security model:
   - Only dashboards explicitly enabled for embedding by an admin are returned.
   - Tokens are signed with `embedding-secret-key` — they cannot be forged.
   - Tokens grant read-only, view-only access (no edit capabilities).
   - Endpoint requires `enable-embedding-static` to be enabled globally.
   - Endpoint adds CORS header to allow the Agora origin to call it."
  (:require
   [buddy.sign.jwt :as jwt]
   [metabase.api.macros :as api.macros]
   [metabase.embedding.settings :as embedding.settings]
   [metabase.util.i18n :refer [tru]]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

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

(def ^{:doc "Routes for `/api/agora`"
       :arglists '([request respond raise])} routes
  (api.macros/ns-handler *ns* wrap-cors))
