(ns metabase.metabot.config
  (:require
   [metabase.api.common :as api]
   [metabase.llm.settings :as llm.settings]
   [metabase.metabot.db :as metabot.db]
   [metabase.metabot.settings :as metabot.settings]))

(def internal-metabot-id
  "The entity ID of the internal Metabot instance."
  "metabotmetabotmetabot")

(def embedded-metabot-id
  "The entity ID of the embedded Metabot instance."
  "embeddedmetabotmetabo")

(def legacy-metabot-ids
  "Legacy UUIDs mapped to Metabot entity IDs."
  {"b5716059-ad40-4d83-a4e1-673af020b2d8" internal-metabot-id
   "c61bf5f5-1025-47b6-9298-bf1827105bb6" embedded-metabot-id})

(defn any-metabot-enabled?
  "Returns true if at least one of the metabot instances (internal or embedded) is enabled."
  []
  (and (llm.settings/ai-features-enabled?)
       (or (metabot.settings/metabot-enabled?)
           (metabot.settings/embedded-metabot-enabled?))))

(defn check-metabot-enabled!
  "Throws a 403 if metabot is not enabled. When called with no arguments, checks that at least one metabot instance is
   enabled. When called with a Metabot row, checks the specific instance's setting."
  ([]
   (api/check (llm.settings/ai-features-enabled?)
              [403 "AI features are not enabled."])
   (api/check (any-metabot-enabled?)
              [403 "Metabot is not enabled."]))
  ([metabot]
   (api/check (llm.settings/ai-features-enabled?)
              [403 "AI features are not enabled."])
   (if (= (:entity_id metabot) embedded-metabot-id)
     (api/check (metabot.settings/embedded-metabot-enabled?)
                [403 "Embedded Metabot is not enabled."])
     (api/check (metabot.settings/metabot-enabled?)
                [403 "Metabot is not enabled."]))))

(defn metabot-id->profile-id
  "Return the configured profile ID for a Metabot entity ID, or nil."
  [metabot-id]
  (get {internal-metabot-id "internal"
        embedded-metabot-id "embedding_next"}
       metabot-id))

(defn resolve-dynamic-metabot-id
  "Resolve an explicit ID, the configured ID, or the internal Metabot entity ID.

  Maps UUIDs in [[legacy-metabot-ids]] to their entity IDs."
  [metabot-id]
  (let [metabot-id (or metabot-id
                       (metabot.settings/metabot-id)
                       internal-metabot-id)]
    (get legacy-metabot-ids metabot-id metabot-id)))

(defn find-metabot
  "Return the Metabot row for an entity ID, a legacy UUID, or a numeric primary key, or nil.

  A nil ID uses the configured or internal Metabot."
  [metabot-id]
  (if (integer? metabot-id)
    (metabot.db/metabot metabot-id)
    (metabot.db/metabot-by-entity-id (resolve-dynamic-metabot-id metabot-id))))

(defn resolve-metabot
  "Return the Metabot row for an entity ID, a legacy UUID, or a numeric primary key.

  A nil ID uses the configured or internal Metabot. Throws a 400 if the ID matches no Metabot."
  [metabot-id]
  (let [metabot (find-metabot metabot-id)]
    (api/check metabot [400 "Unknown Metabot."])
    metabot))

(defn resolve-dynamic-profile-id
  "Resolve the profile ID: analytics-only override > explicit profile-id > metabot-id->profile-id > embedding_next.
   Throws a 400 for retired profiles without a replacement."
  ([profile-id]
   (resolve-dynamic-profile-id profile-id (resolve-dynamic-metabot-id nil)))
  ([profile-id metabot-id]
   (let [profile-id (or (when (metabot.settings/anomaly-analytics-only) "anomaly-analytics")
                        profile-id
                        (metabot-id->profile-id metabot-id)
                        "embedding_next")]
     (api/check (not= (some-> profile-id name) "transforms_codegen")
                [400 "Transform code generation is no longer supported."])
     profile-id)))

(defn check-anomaly-database-isolation!
  "Fail closed before an analytics turn can read context or invoke inference if its instance catalog is not exactly curated.
   This also guards direct agent calls that bypass the HTTP profile override. Never returns source names or credentials."
  [profile-id]
  (when (or (metabot.settings/anomaly-analytics-only)
            (= (some-> profile-id name) "anomaly-analytics"))
    (api/check-403 (= (some-> profile-id name) "anomaly-analytics"))
    (let [allowed (metabot.settings/anomaly-analytics-database-ids)]
      (api/check-403 (and (sequential? allowed)
                          (seq allowed)
                          (every? pos-int? allowed)
                          (= (set allowed) (set (metabot.db/all-database-ids))))))))

(defn check-anomaly-query!
  "Recheck the curated catalog and target immediately before a Metabot query executes on an analytics-only instance."
  [query]
  (when (metabot.settings/anomaly-analytics-only)
    (check-anomaly-database-isolation! :anomaly-analytics)
    (api/check-403 (contains? (set (metabot.settings/anomaly-analytics-database-ids)) (:database query)))))
