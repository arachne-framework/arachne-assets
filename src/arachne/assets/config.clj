(ns arachne.assets.config
  (:require [arachne.core.config :as cfg]))

(defn configure-input-dependencies
  "Configure pipeline components to have a component dependency on all their pipeline inputs."
  [cfg]
  (cfg/with-provenance :module `configure-input-dependencies
    (let [components (cfg/q cfg '[:find ?consumer ?producer
                                  :where
                                  [?consumer :arachne.assets.consumer/inputs ?input]
                                  [?input :arachne.assets.input/entity ?producer]])
          txdata (map (fn [[consumer producer]]
                        {:db/id consumer
                         :arachne.component/dependencies [{:arachne.component.dependency/entity producer}]})
                   components)]
      (if (seq txdata)
        (cfg/update cfg txdata)
        cfg))))