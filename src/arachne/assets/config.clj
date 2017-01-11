(ns arachne.assets.config
  (:require [arachne.core.config :as cfg]))

(defn configure-input-dependencies
  "Configure pipeline components to have a component dependency on all their pipeline inputs."
  [cfg]
  (cfg/with-provenance :module `configure-input-dependencies
    (let [components (cfg/q cfg '[:find ?component ?input
                                  :where
                                  [?component :arachne.assets.consumer/inputs ?input]])
          txdata (map (fn [[component input]]
                        {:db/id component
                         :arachne.component/dependencies [{:arachne.component.dependency/entity input}]})
                   components)]
      (if (seq txdata)
        (cfg/update cfg txdata)
        cfg))))

(comment

  (def cfg the-cfg)

  (cfg/q cfg '[:find ?component
               :where
               [?component :arachne/id :test/output]
               ])

  (cfg/pull cfg '[*] 17592186045437)


  )