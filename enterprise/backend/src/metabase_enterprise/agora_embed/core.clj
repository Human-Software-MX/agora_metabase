(ns metabase-enterprise.agora-embed.core
  "Enterprise implementation of the multi-tenant Agora embedding flow.

   Identity comes from a JWT that the Agora backend signs with the JWT SSO shared secret (`jwt-shared-secret`).
   The token is verified by the regular JWT SSO provider, so it follows the same contract as `/auth/sso?jwt=`:
   `email`, `first_name`, `last_name`, and the tenant claim (`@tenant` by default, see `jwt-attribute-tenant`).
   Optional `@tenant.attributes` and `groups` claims are honoured by the provider as well.

   Isolation between tenants is provided by Metabase's Tenants feature: a tenant user only sees their tenant
   collection, the shared tenant collections and the databases that their tenant groups are granted. On top of
   that, every Agora login guarantees the user belongs to a tenant group named after the tenant slug, so an admin
   can grant each tenant access to its own database connections from Admin > Permissions."
  (:require
   ;; loaded for their side effects: they register the JWT provider and the tenant provisioning hook
   [metabase-enterprise.sso.init]
   [metabase-enterprise.sso.settings :as sso-settings]
   [metabase-enterprise.tenants.auth-provider]
   [metabase.auth-identity.core :as auth-identity]
   [metabase.permissions.core :as perms]
   [metabase.premium-features.core :refer [defenterprise]]
   [metabase.request.core :as request]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn- assert-tenants-ready!
  "Throw a 503 unless the instance is configured for multi-tenant Agora embedding."
  []
  (when-not (perms/use-tenants)
    (throw (ex-info (tru "Tenants are not enabled. Turn on the multi-tenant user strategy in Admin > People.")
                    {:status-code 503})))
  (when-not (sso-settings/jwt-enabled)
    (throw (ex-info (tru "JWT authentication is not configured. Set a shared secret in Admin > Settings > Authentication > JWT.")
                    {:status-code 503}))))

(defn- authenticate-tenant-jwt
  "Verify `jwt` with the JWT SSO provider without creating users, tenants or sessions. Returns the authentication
   result map. Throws 401 when the token is invalid and 400 when it carries no tenant claim."
  [jwt]
  (let [{:keys [success? tenant-slug message]
         :as   result} (auth-identity/authenticate :provider/jwt {:token jwt})]
    (when-not (true? success?)
      (throw (ex-info (or message (tru "Invalid Agora token."))
                      {:status-code 401})))
    (when-not (seq tenant-slug)
      (throw (ex-info (tru "The Agora token must include the tenant claim ({0})." (sso-settings/jwt-attribute-tenant))
                      {:status-code 400})))
    result))

(defn- tenant-collection-ids
  "The tenant's root collection ID plus the IDs of all of its non-archived descendants."
  [tenant-collection-id]
  (conj (t2/select-pks-set :model/Collection
                           :location [:like (str "/" tenant-collection-id "/%")]
                           :archived false)
        tenant-collection-id))

(defn- present-tenant [{:keys [id slug name tenant_collection_id]}]
  {:id            id
   :slug          slug
   :name          name
   :collection_id tenant_collection_id})

(defenterprise verify-tenant-jwt
  "Verify an Agora tenant JWT and return the tenant it refers to (`nil` when the tenant has not been provisioned
   yet). Has no side effects."
  :feature :tenants
  [jwt]
  (assert-tenants-ready!)
  (let [{:keys [tenant-slug]} (authenticate-tenant-jwt jwt)]
    (some-> (t2/select-one :model/Tenant :slug tenant-slug :is_active true)
            present-tenant)))

(defenterprise tenant-dashboards
  "Return the non-archived dashboards that live in the tenant collection tree of the tenant named in `jwt`, ordered
   by name. Returns an empty sequence when the tenant does not exist yet. Dashboards in shared tenant collections are
   deliberately excluded: static embeds run without a user, so tenant-aware data permissions would not apply."
  :feature :tenants
  [jwt]
  (assert-tenants-ready!)
  (let [{:keys [tenant-slug]} (authenticate-tenant-jwt jwt)]
    (if-let [{:keys [tenant_collection_id]} (t2/select-one :model/Tenant :slug tenant-slug :is_active true)]
      (t2/select :model/Dashboard
                 :collection_id [:in (tenant-collection-ids tenant_collection_id)]
                 :archived false
                 {:order-by [[:name :asc]]})
      [])))

(defn- ensure-tenant-group!
  "Return the tenant group named after the tenant slug, creating it when missing. New tenant groups start with the
   most restrictive data permissions, so nothing is exposed until an admin grants access."
  [{:keys [slug] :as _tenant}]
  (or (t2/select-one :model/PermissionsGroup :name slug :is_tenant_group true)
      (do
        (when (t2/exists? :model/PermissionsGroup :name slug)
          (throw (ex-info (tru "A regular group named ''{0}'' already exists; rename it so the tenant group can be created." slug)
                          {:status-code 409})))
        (log/infof "Creating tenant group %s for Agora tenant" slug)
        (t2/insert-returning-instance! :model/PermissionsGroup {:name slug :is_tenant_group true}))))

(defn- ensure-tenant-group-membership!
  [user-id tenant]
  (let [group (ensure-tenant-group! tenant)]
    (when-not (t2/exists? :model/PermissionsGroupMembership :user_id user-id :group_id (:id group))
      (perms/add-user-to-group! user-id (:id group)))
    group))

(defenterprise tenant-login!
  "Log a tenant user in from an Agora JWT and return `{:session ... :user ... :tenant ...}`.

   Delegates to the JWT SSO provider, which verifies the token, provisions the tenant and the user when needed,
   and applies the usual tenant validations (tenant mismatch, inactive tenant, internal user with a tenant claim).
   `embedded-request` is bound as the current request while the session is created so that the session gets the
   `:full-app-embed` type and an anti-CSRF token."
  :feature :tenants
  [jwt embedded-request]
  (assert-tenants-ready!)
  ;; Reject tokens without a tenant claim *before* the login flow runs, otherwise the JWT provider would provision an
  ;; internal (non-tenant) user for them.
  (authenticate-tenant-jwt jwt)
  (let [result (request/with-current-request embedded-request
                 (auth-identity/login! :provider/jwt {:token       jwt
                                                      :device-info (request/device-info embedded-request)}))]
    (when-not (true? (:success? result))
      (throw (ex-info (or (:message result) (tru "Agora login failed."))
                      {:status-code 401
                       :error       (:error result)})))
    (let [user   (t2/select-one [:model/User :id :email :tenant_id] :id (-> result :user :id))
          tenant (some->> (:tenant_id user) (t2/select-one :model/Tenant :id))]
      (when-not tenant
        (throw (ex-info (tru "Agora sessions must belong to a tenant user.")
                        {:status-code 403})))
      (request/as-admin
        (ensure-tenant-group-membership! (:id user) tenant))
      {:session (:session result)
       :user    user
       :tenant  (present-tenant tenant)})))
