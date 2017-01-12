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

(defn configure-transform-dependencies
  "Configure transform components to have a component dependency on their transformation"
  [cfg]
  (cfg/with-provenance :module `configure-transform-dependencies
    (let [components (cfg/q cfg '[:find ?component ?transformer
                                  :where
                                  [?component :arachne.assets.transform/transformer ?transformer]])
          txdata (map (fn [[component transformer]]
                        {:db/id component
                         :arachne.component/dependencies [{:arachne.component.dependency/entity transformer
                                                           :arachne.component.dependency/key :transformer}]})
                   components)]
      (if (seq txdata)
        (cfg/update cfg txdata)
        cfg))))