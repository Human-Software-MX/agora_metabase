(ns metabase-enterprise.agora-embed.api-test
  "Tests for the multi-tenant mode of the `/api/agora/embed/*` endpoints."
  (:require
   [buddy.sign.jwt :as jwt]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase-enterprise.sso.test-setup :as sso.test-setup]
   [metabase.request.core :as request]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]
   [toucan2.core :as t2]))

;; the SSO test setup derives `site-url` from the running test web server, so it has to be started
(use-fixtures :once (fixtures/initialize :web-server :test-users))

(def ^:private embedding-secret "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

(defn- agora-jwt
  "Sign an Agora tenant token the way the Agora backend does."
  ([tenant-slug] (agora-jwt tenant-slug {}))
  ([tenant-slug extra-claims]
   (jwt/sign (merge {:email      (str tenant-slug "-user@agora.test")
                     :first_name "Agora"
                     :last_name  "User"
                     "@tenant"   tenant-slug}
                    extra-claims)
             sso.test-setup/default-jwt-secret)))

(defmacro ^:private with-agora-setup!
  "JWT SSO configured, tenants enabled, static embedding enabled, and model cleanup for everything the endpoints may
   provision."
  [& body]
  `(sso.test-setup/with-jwt-default-setup!
     (mt/with-additional-premium-features #{:tenants :embedding}
       (mt/with-temporary-setting-values [use-tenants            true
                                          enable-embedding-static true
                                          embedding-secret-key    embedding-secret]
         (mt/with-model-cleanup [:model/PermissionsGroup :model/Dashboard]
           ~@body)))))

(defn- location [response]
  (get-in response [:headers "Location"]))

(defn- embedded-session-cookie?
  "True when the response sets the embedded (full-app embed) session cookie, mirroring
   [[sso.test-setup/successful-login?]]."
  [response]
  (or (string? (get-in response [:cookies request/metabase-embedded-session-cookie :value]))
      (some #(str/starts-with? % request/metabase-embedded-session-cookie)
            (get-in response [:headers "Set-Cookie"]))))

;;; NOTE: `/api/agora` is wrapped in `+message-only-exceptions`, so every error surfaces as a 400 whose body is the
;;; exception message, regardless of the status code the endpoint threw with.

;;; ------------------------------------------------ /embed/dashboards ------------------------------------------------

(deftest tenant-dashboards-are-scoped-to-the-tenant-collection-tree-test
  (with-agora-setup!
    (mt/with-temp [:model/Tenant {acme-coll :tenant_collection_id} {:slug "acme" :name "Acme"}
                   :model/Tenant {globex-coll :tenant_collection_id} {:slug "globex" :name "Globex"}
                   :model/Collection {acme-sub :id} {:name "Acme sub" :location (format "/%d/" acme-coll)
                                                     :namespace "tenant-specific"}
                   :model/Dashboard _ {:name "Acme root dash" :collection_id acme-coll :enable_embedding true}
                   :model/Dashboard _ {:name "Acme sub dash" :collection_id acme-sub}
                   :model/Dashboard _ {:name "Acme archived" :collection_id acme-coll :archived true}
                   :model/Dashboard _ {:name "Globex dash" :collection_id globex-coll :enable_embedding true}
                   :model/Dashboard _ {:name "Root dash" :collection_id nil :enable_embedding true}]
      (testing "only dashboards in the tenant's collection tree are returned, embed_url only when embeddable"
        (let [{:keys [dashboards]} (mt/client :get 200 "agora/embed/dashboards" :jwt (agora-jwt "acme"))]
          (is (= ["Acme root dash" "Acme sub dash"] (map :name dashboards)))
          (is (=? [{:collection_id acme-coll
                    :app_url       #"^/dashboard/\d+$"
                    :embed_url     #"^/embed/dashboard/.+"}
                   {:collection_id acme-sub
                    :app_url       #"^/dashboard/\d+$"
                    :embed_url     nil}]
                  dashboards))))
      (testing "an unknown tenant sees nothing"
        (is (= {:dashboards []}
               (mt/client :get 200 "agora/embed/dashboards" :jwt (agora-jwt "nobody")))))
      (testing "an invalid token is rejected"
        (is (= "Message seems corrupt or manipulated"
               (mt/client :get 400 "agora/embed/dashboards"
                          :jwt (jwt/sign {:email "x@agora.test" "@tenant" "acme"} "wrong-secret")))))
      (testing "a token without the tenant claim is rejected"
        (is (re-find #"tenant claim"
                     (mt/client :get 400 "agora/embed/dashboards"
                                :jwt (jwt/sign {:email "x@agora.test"} sso.test-setup/default-jwt-secret)))))
      (testing "legacy mode without a token still lists embeddable root dashboards"
        (is (= ["Root dash"]
               (map :name (:dashboards (mt/client :get 200 "agora/embed/dashboards")))))))))

(deftest tenant-mode-requires-tenants-to-be-enabled-test
  (with-agora-setup!
    (mt/with-temporary-setting-values [use-tenants false]
      (is (re-find #"Tenants are not enabled"
                   (mt/client :get 400 "agora/embed/dashboards" :jwt (agora-jwt "acme")))))))

;;; ---------------------------------------------- /embed/collection-url ----------------------------------------------

(deftest collection-url-tenant-mode-test
  (with-agora-setup!
    (mt/with-temp [:model/Tenant {tenant-id :id, acme-coll :tenant_collection_id} {:slug "acme" :name "Acme"}]
      (testing "returns an auth URL carrying the jwt and the tenant when it already exists"
        (let [token (agora-jwt "acme")
              {:keys [embed_url tenant]} (mt/client :get 200 "agora/embed/collection-url" :jwt token)]
          (is (= (str "/api/agora/embed/collection-auth?jwt=" token) embed_url))
          (is (= {:id tenant-id :slug "acme" :name "Acme" :collection_id acme-coll} tenant))))
      (testing "return_to is forwarded"
        (is (str/ends-with? (:embed_url (mt/client :get 200 "agora/embed/collection-url"
                                                   :jwt (agora-jwt "acme") :return_to "/dashboard/1"))
                            "&return_to=%2Fdashboard%2F1")))
      (testing "tenant is nil before the tenant's first login"
        (is (nil? (:tenant (mt/client :get 200 "agora/embed/collection-url" :jwt (agora-jwt "newco"))))))
      (testing "return_to must be relative"
        (is (= "return_to must be a relative path."
               (mt/client :get 400 "agora/embed/collection-url"
                          :jwt (agora-jwt "acme") :return_to "https://evil.example"))))
      (testing "legacy mode still requires return_to"
        (is (= "return_to is required."
               (mt/client :get 400 "agora/embed/collection-url")))))))

;;; --------------------------------------------- /embed/collection-auth ----------------------------------------------

(deftest collection-auth-provisions-tenant-user-and-group-test
  (with-agora-setup!
    (let [token    (agora-jwt "acme" {:email "mittens@agora.test"})
          response (mt/client-full-response :get 302 "agora/embed/collection-auth" :jwt token)]
      (testing "the tenant and the user are provisioned and linked"
        (let [tenant (t2/select-one :model/Tenant :slug "acme")
              user   (t2/select-one :model/User :email "mittens@agora.test")]
          (is (some? tenant))
          (is (= (:id tenant) (:tenant_id user)))
          (testing "the user is a member of a tenant group named after the tenant slug"
            (let [group (t2/select-one :model/PermissionsGroup :name "acme")]
              (is (true? (:is_tenant_group group)))
              (is (t2/exists? :model/PermissionsGroupMembership :user_id (:id user) :group_id (:id group)))))
          (testing "the browser is redirected into the tenant collection with an anti-CSRF token"
            (is (re-matches (re-pattern (format "/collection/%d\\?mb_anti_csrf_token=.+" (:tenant_collection_id tenant)))
                            (location response))))
          (testing "an embedded (full-app) session cookie is set"
            (is (embedded-session-cookie? response))
            ;; the session type is not stored; an anti-CSRF token is only generated for full-app-embed sessions
            (is (string? (t2/select-one-fn :anti_csrf_token :model/Session
                                           :user_id (:id user) {:order-by [[:created_at :desc]]}))))))
      (testing "logging in again reuses the group and honours return_to"
        (let [response (mt/client-full-response :get 302 "agora/embed/collection-auth"
                                                :jwt (agora-jwt "acme" {:email "mittens@agora.test"})
                                                :return_to "/dashboard/7")]
          (is (str/starts-with? (location response) "/dashboard/7?mb_anti_csrf_token="))
          (is (= 1 (t2/count :model/PermissionsGroup :name "acme")))
          (is (= 1 (t2/count :model/User :email "mittens@agora.test"))))))))

(deftest collection-auth-rejects-bad-input-test
  (with-agora-setup!
    (testing "an internal user cannot be logged in with a tenant claim"
      (is (= "Cannot add tenant claim to internal user"
             (mt/client :get 400 "agora/embed/collection-auth"
                        :jwt (agora-jwt "acme" {:email "rasta@metabase.com"})))))
    (testing "a token without the tenant claim is rejected before anything is provisioned"
      (is (re-find #"tenant claim"
                   (mt/client :get 400 "agora/embed/collection-auth"
                              :jwt (jwt/sign {:email "x@agora.test"} sso.test-setup/default-jwt-secret))))
      (is (not (t2/exists? :model/User :email "x@agora.test"))))
    (testing "return_to must be relative"
      (is (= "return_to must be a relative path."
             (mt/client :get 400 "agora/embed/collection-auth"
                        :jwt (agora-jwt "acme") :return_to "//evil.example"))))
    (testing "exactly one of jwt or token is required"
      (is (= "Exactly one of jwt or token is required."
             (mt/client :get 400 "agora/embed/collection-auth")))
      (is (= "Exactly one of jwt or token is required."
             (mt/client :get 400 "agora/embed/collection-auth" :jwt (agora-jwt "acme") :token "x"))))))
