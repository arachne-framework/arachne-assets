(ns arachne.assets
  (:require [arachne.core.config :as cfg]
            [arachne.assets.schema :as schema]
            [arachne.assets.config :as config]
            [arachne.assets.specs]))

(defn schema
  "Return the schema for the assets module"
  []
  schema/schema)

(defn configure
  "Configure the assets module"
  [cfg]
  (-> cfg
    (config/configure-inputs)
    (config/configure-outputs)
    (config/configure-transforms)
    (config/configure-merges)))
