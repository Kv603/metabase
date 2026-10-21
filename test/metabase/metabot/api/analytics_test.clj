(ns metabase.metabot.api.analytics-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [metabase.api.common :as api]
   [metabase.channel.render.core :as render]
   [metabase.lib-be.core :as lib-be]
   [metabase.lib.core :as lib]
   [metabase.metabot.agent.core :as agent]
   [metabase.metabot.agent.profiles :as profiles]
   [metabase.metabot.api :as metabot-api]
   [metabase.metabot.api.analytics :as analytics]
   [metabase.metabot.config :as config]
   [metabase.metabot.conversation-title :as conversation-title]
   [metabase.metabot.db :as metabot.db]
   [metabase.metabot.persistence :as persistence]
   [metabase.metabot.query-execution :as query-execution]
   [metabase.metabot.settings :as settings]
   [metabase.metabot.tools.legacy :as legacy]
   [metabase.metabot.usage :as usage]
   [metabase.query-permissions.core :as query-perms]
   [metabase.test :as mt]
   [metabase.util.malli.registry :as mr]))

(deftest render-requires-authentication-test
  (is (= "Unauthenticated" (mt/client :post 401 "metabot/analytics/render"
                                      {:type "saved_card" :card_id 1}))))

(deftest render-checks-conversation-before-reading-state-test
  (let [read-state? (atom false)]
    (with-redefs [api/read-check (fn [& _] (throw (ex-info "Forbidden" {:status-code 403})))
                  persistence/live-messages (fn [_] (reset! read-state? true))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Forbidden"
                            (analytics/render-reference {:type "conversation_chart"
                                                         :conversation_id (str (random-uuid))
                                                         :chart_id (str (random-uuid))})))
      (is (false? @read-state?)))))

(deftest render-checks-current-permissions-before-executing-test
  (let [executed? (atom false)]
    (with-redefs [api/read-check (fn [& _] {:dataset_query {:database 7} :display :bar})
                  lib-be/normalize-query identity
                  lib/prepare-for-serialization identity
                  lib/any-native-stage? (constantly false)
                  settings/anomaly-analytics-database-ids (constantly [7])
                  query-perms/check-run-permissions-for-query (fn [_] (throw (ex-info "Forbidden" {:status-code 403})))
                  query-execution/execute-page! (fn [& _] (reset! executed? true))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Forbidden"
                            (analytics/render-reference {:type "saved_card" :card_id 1})))
      (is (false? @executed?)))))

(deftest render-fails-closed-without-curated-database-test
  (with-redefs [api/read-check (fn [& _] {:dataset_query {:database 7} :display :bar})
                lib-be/normalize-query identity
                settings/anomaly-analytics-database-ids (constantly [])]
    (is (= 403 (try (analytics/render-reference {:type "saved_card" :card_id 1})
                    (catch clojure.lang.ExceptionInfo e (:status-code (ex-data e))))))))

(deftest render-enforces-resource-bounds-test
  (let [png (byte-array [1 2 3])
        calls (atom [])]
    (with-redefs [api/read-check (fn [& _] {:dataset_query {:database 7} :display :bar})
                  lib-be/normalize-query identity
                  lib/prepare-for-serialization identity
                  lib/any-native-stage? (constantly false)
                  settings/anomaly-analytics-database-ids (constantly [7])
                  query-perms/check-run-permissions-for-query (constantly nil)
                  query-execution/execute-page! (fn [& args] (swap! calls conj args)
                                                  {:rows [[1]] :cols [{:name "n"}] :truncated? false})
                  render/render-adhoc-card-to-png (fn [_ _ width _] (is (= 1200 width)) png)]
      (let [response (analytics/render-reference {:type "saved_card" :card_id 1})]
        (is (= "image/png" (get-in response [:headers "Content-Type"])))
        (is (= "no-store" (get-in response [:headers "Cache-Control"])))
        (is (= png (:body response)))
        (is (= [[{:database 7} 1000 :metabot {:timeout-ms 30000}]] @calls))))))

(deftest render-refuses-inline-native-queries-test
  (let [executed? (atom false)]
    (with-redefs [api/read-check (fn [& _] {:dataset_query {:database 7} :display :bar})
                  lib-be/normalize-query identity
                  lib/any-native-stage? (constantly true)
                  settings/anomaly-analytics-database-ids (constantly [7])
                  query-execution/execute-page! (fn [& _] (reset! executed? true))]
      (is (= 400 (try (analytics/render-reference {:type "saved_card" :card_id 1})
                      (catch clojure.lang.ExceptionInfo e (:status-code (ex-data e))))))
      (is (false? @executed?)))))

(deftest render-refuses-incomplete-or-oversized-results-test
  (doseq [page [{:rows [[1]] :cols [{:name "n"}] :truncated? true}
               {:rows [[1]] :cols (repeat 31 {:name "n"}) :truncated? false}
               {:rows [[(apply str (repeat 4097 "x"))]] :cols [{:name "n"}] :truncated? false}]]
    (let [rendered? (atom false)]
      (with-redefs [api/read-check (fn [& _] {:dataset_query {:database 7} :display :bar})
                    lib-be/normalize-query identity
                    lib/prepare-for-serialization identity
                    lib/any-native-stage? (constantly false)
                    settings/anomaly-analytics-database-ids (constantly [7])
                    query-perms/check-run-permissions-for-query (constantly nil)
                    query-execution/execute-page! (constantly page)
                    render/render-adhoc-card-to-png (fn [& _] (reset! rendered? true))]
        (is (= 400 (try (analytics/render-reference {:type "saved_card" :card_id 1})
                        (catch clojure.lang.ExceptionInfo e (:status-code (ex-data e))))))
        (is (false? @rendered?))))))

(deftest anomaly-profile-has-no-mutation-tools-test
  (let [profile (profiles/get-profile :anomaly-analytics)
        names (set (map #(-> % legacy/declaration-of :name) (:tools profile)))]
    (is (= #{"search" "read_resource" "construct_notebook_query" "run_query" "create_chart" "static_viz"} names))
    (is (false? (:skills? profile)))))

(deftest anomaly-profile-obeys-native-schema-and-permission-gate-test
  (is (mr/validate ::agent/profile-id :anomaly-analytics))
  (let [check! #'agent/check-metabot-access!]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"permission"
                          (check! :anomaly-analytics {:permission/metabot :no :permission/metabot-nlq :yes})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"permission"
                          (check! :anomaly-analytics {:permission/metabot :yes :permission/metabot-nlq :no})))
    (is (nil? (check! :anomaly-analytics {:permission/metabot :yes :permission/metabot-nlq :yes})))))

(deftest curated-instance-overrides-caller-profile-test
  (testing "a native UI or API caller cannot select a more powerful profile on this instance"
    (with-redefs [settings/anomaly-analytics-only (constantly true)]
      (is (= "anomaly-analytics" (config/resolve-dynamic-profile-id "internal" config/internal-metabot-id)))
      (is (= "anomaly-analytics" (config/resolve-dynamic-profile-id nil config/internal-metabot-id))))))

(deftest analytics-catalog-isolation-is-enforced-every-turn-test
  (with-redefs [settings/anomaly-analytics-only (constantly true)
                settings/anomaly-analytics-database-ids (constantly [7])]
    (testing "the exact curated catalog may be used"
      (with-redefs [metabot.db/all-database-ids (constantly [7])]
        (is (true? (config/check-anomaly-database-isolation! :anomaly-analytics)))))
    (testing "adding a source later disables analytics, even if that source is invisible to this user"
      (with-redefs [metabot.db/all-database-ids (constantly [7 8])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"permissions"
                              (config/check-anomaly-database-isolation! :anomaly-analytics)))))
    (testing "direct agent calls cannot bypass the forced read-only profile"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"permissions"
                            (config/check-anomaly-database-isolation! :internal))))))

(deftest analytics-catalog-empty-or-malformed-allowlist-fails-closed-test
  (doseq [allowed [[] nil ["7"] {}]]
    (with-redefs [settings/anomaly-analytics-only (constantly false)
                  settings/anomaly-analytics-database-ids (constantly allowed)
                  metabot.db/all-database-ids (constantly [7])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"permissions"
                            (config/check-anomaly-database-isolation! :anomaly-analytics))))))

(deftest analytics-query-target-is-checked-before-execution-test
  (with-redefs [settings/anomaly-analytics-only (constantly true)
                settings/anomaly-analytics-database-ids (constantly [7])
                metabot.db/all-database-ids (constantly [7])]
    (is (true? (config/check-anomaly-query! {:database 7})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"permissions"
                          (query-execution/execute-page! {:database 8} 1000 :metabot {:timeout-ms 30000})))
    (with-redefs [metabot.db/all-database-ids (constantly [7 8])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"permissions"
                            (query-execution/execute-page! {:database 7} 1000 :metabot {:timeout-ms 30000}))))))

(deftest native-streaming-accepts-anomaly-profile-only-with-curated-catalog-test
  (let [received (atom nil)
        started (atom 0)
        request {:message "Explain the attendance report" :context {}
                 :conversation_id (str (random-uuid)) :profile_id "anomaly-analytics"}]
    (mt/with-dynamic-fn-redefs [settings/anomaly-analytics-only (constantly true)
                                settings/anomaly-analytics-database-ids (constantly [7])
                                metabot.db/all-database-ids (constantly [7])
                                config/check-metabot-enabled! (constantly nil)
                                usage/check-metabase-managed-free-limit! (constantly nil)
                                metabot-api/check-conversation-access! (constantly nil)
                                persistence/leaf-external-id (constantly nil)
                                persistence/live-messages (constantly [])
                                persistence/history (constantly [])
                                persistence/start-turn! (fn [& _]
                                                          (swap! started inc)
                                                          {:assistant-msg-id 1 :assistant-external-id (str (random-uuid))})
                                conversation-title/ensure-title! (constantly {:status :missing})
                                metabot-api/native-agent-streaming-request (fn [args] (reset! received args) nil)]
      (metabot-api/streaming-request request {})
      (is (= "anomaly-analytics" (:profile-id @received)))
      (is (= 1 @started))
      (reset! received nil)
      (testing "a subsequently attached source blocks the next turn before persistence or inference"
        (with-redefs [metabot.db/all-database-ids (constantly [7 8])]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"permissions"
                                (metabot-api/streaming-request request {}))))
        (is (nil? @received))
        (is (= 1 @started))))))
