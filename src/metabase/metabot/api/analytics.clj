(ns metabase.metabot.api.analytics
  "Authenticated, bounded chart rendering for the remote anomaly worker. Accepts references, never queries or URLs."
  (:require
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.api.routes.common :refer [+auth]]
   [metabase.channel.render.core :as render]
   [metabase.lib-be.core :as lib-be]
   [metabase.lib.core :as lib]
   [metabase.metabot.persistence :as persistence]
   [metabase.metabot.query-execution :as query-execution]
   [metabase.metabot.settings :as settings]
   [metabase.query-permissions.core :as query-perms]
   [metabase.util.malli.schema :as ms]))

(set! *warn-on-reflection* true)

(def ^:private supported-displays #{:table :bar :line :area :row :pie :scatter :funnel})

(defn- conversation-chart
  [{:keys [conversation_id chart_id]}]
  ;; Conversation ownership is checked before reading any stored query or chart metadata.
  (api/read-check :model/MetabotConversation conversation_id)
  (let [messages (filter #(and (:finished %) (nil? (:error %))) (persistence/live-messages conversation_id))
        state    (persistence/conversation-state messages)
        chart    (api/check-404 (get-in state [:charts chart_id]))
        query    (api/check-404 (get-in state [:queries (:query_id chart)]))]
    {:dataset_query query
     :display       (or (get-in chart [:visualization_settings :chart_type]) "table")
     :visualization_settings {}}))

(defn render-reference
  "Resolve a server-owned chart, recheck current data permissions, and render at fixed resource bounds."
  [{:keys [type card_id] :as reference}]
  (let [card       (case type
                     "saved_card" (api/check-not-archived (api/read-check :model/Card card_id))
                     "conversation_chart" (conversation-chart reference))
        query      (lib-be/normalize-query (:dataset_query card))
        _          (api/check-400 (seq query) "Chart query is unavailable.")
        _          (api/check-403 (contains? (set (settings/anomaly-analytics-database-ids)) (:database query)))
        ;; The reporting connection itself is SELECT-only; inline native queries are not accepted here.
        _          (api/check-400 (not (lib/any-native-stage? query)) "Only notebook charts can be rendered.")
        serialized (lib/prepare-for-serialization query)
        _          (query-perms/check-run-permissions-for-query serialized)
        display    (keyword (:display card))
        _          (api/check-400 (contains? supported-displays display) "Unsupported chart display.")
        page       (query-execution/execute-page! serialized 1000 :metabot {:timeout-ms 30000})
        _          (api/check-400 (not (:truncated? page)) "Chart exceeds 1000 rows; narrow the query.")
        _          (api/check-400 (<= (count (:cols page)) 30) "Chart exceeds 30 columns.")
        cell-sizes (map #(count (str %)) (mapcat identity (:rows page)))
        _          (api/check-400 (and (every? #(<= % 4096) cell-sizes)
                                      (<= (reduce + 0 cell-sizes) (* 2 1024 1024)))
                                  "Chart contains too much text; narrow the query.")
        results    {:status :completed :data {:rows (:rows page) :cols (:cols page)}}
        ;; Do not render arbitrary saved visualization options such as remote image URLs.
        png        (render/render-adhoc-card-to-png
                    {:display display :visualization_settings {}}
                    results 1200 {:channel.render/include-title? false})]
    (api/check-400 (<= (alength ^bytes png) (* 8 1024 1024)) "Rendered chart exceeds 8 MiB.")
    {:status 200
     :headers {"Content-Type" "image/png" "Cache-Control" "no-store" "X-Content-Type-Options" "nosniff"}
     :body png}))

(api.macros/defendpoint :post "/render" :- :any
  "Render a permitted saved question or a chart belonging to an accessible conversation as PNG."
  [_route-params _query-params
   reference :- [:multi {:dispatch :type}
                 ["saved_card" [:map {:closed true}
                                [:type [:= "saved_card"]]
                                [:card_id ms/PositiveInt]]]
                 ["conversation_chart" [:map {:closed true}
                                        [:type [:= "conversation_chart"]]
                                        [:conversation_id ms/UUIDString]
                                        [:chart_id ms/UUIDString]]]]]
  (render-reference reference))

(def ^{:arglists '([request respond raise])} routes
  "`/api/metabot/analytics` routes."
  (api.macros/ns-handler *ns* +auth))
