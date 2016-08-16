(ns arachne.assets
  (:require [arachne.core.config :as cfg]
            [arachne.assets.schema :as schema]))

(defn schema
  "Return the schema for the assets module"
  []
  schema/schema)

(defn configure
  "Configure the assets module"
  [cfg]
  cfg)
