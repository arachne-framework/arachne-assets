(ns arachne.assets.dsl
  (:refer-clojure :exclude [merge])
  (:require [arachne.core.config :as cfg]
            [arachne.core.util :as util]
            [arachne.core.config.init :as script :refer [defdsl]]
            [arachne.assets.dsl.specs]
            [clojure.spec :as s]
            [arachne.error :as e]
            [clojure.string :as str]))

(defn- input
  "Define an asset pipeline input"
  [conformed classpath?]
  (let [id (:arachne-id conformed)
        path (:path conformed)
        includes (some-> conformed :opts :include val)
        excludes (some-> conformed :opts :exclude val)
        watch? (some-> conformed :opts :watch?)
        eid (cfg/tempid)
        tx-map {:db/id eid
                :arachne/id id
                :arachne.assets.input/path path
                :arachne.assets.input/include includes
                :arachne.assets.input/exclude excludes
                :arachne.assets.input/classpath? (when classpath? true)
                :arachne.assets.input/watch? watch?}
        txdata [(util/mkeep tx-map)]]
    (cfg/resolve-tempid (script/transact txdata) eid)))

(defdsl input-dir
  "Define a asset pipeline component that reads from a directory on the file
  system. The path maybe absolute or process-relative. Returns the entity ID of
  the component."
  [& args]
  (input (s/conform (:args (s/get-spec `input-dir)) args) false))

(defn input-resource
  "Define a asset pipeline component that reads from a directory on the
  classpath. Returns the entity ID the component."
  [& args]
  (apply e/assert-args `input-resource args)
  (input (s/conform (:args (s/get-spec `input-resource)) args) false))

(defn- eid
  [eid-or-aid]
  (if (= :eid (key eid-or-aid))
    (val eid-or-aid)
    (cfg/attr @script/*config* [:arachne/id (val eid-or-aid)] :db/id)))

(defdsl output-dir
  "Define a asset pipeline component that writes to a directory on the file
  system."
  [& args]
  (let [conformed (s/conform (:args (s/get-spec `output-dir)) args)
        tid (cfg/tempid)
        id (:arachne-id conformed)
        path (:path conformed)
        input (:input conformed)
        tx-map {:db/id tid
                :arachne/id id
                :arachne.assets.pipeline-element/inputs (eid input)
                :arachne.assets.output/path path}
        txdata [(util/mkeep tx-map)]]
    (cfg/resolve-tempid (script/transact txdata) tid)))

(defdsl transform
  "Define a custom transformer asset pipeline component"
  [& args]
  (let [conformed (s/conform (:args (s/get-spec `transform)) args)
        tid (cfg/tempid)
        id (:arachne-id conformed)
        input (:input conformed)
        transformer (:transformer conformed)
        tx-map {:db/id tid
                :arachne/id id
                :arachne.assets.pipeline-element/inputs (eid input)
                :arachne.assets.transform/transformer (eid transformer)}
        txdata [(util/mkeep tx-map)]]
    (cfg/resolve-tempid (script/transact txdata) tid)))

(defdsl merge
  "Define a merging asset pipeline component"
  [& args]
  (let [conformed (s/conform (:args (s/get-spec `merge)) args)
        tid (cfg/tempid)
        id (:arachne-id conformed)
        inputs (:inputs conformed)
        input-eids (mapv eid inputs)
        tx-map {:db/id tid
                :arachne/id id
                :arachne.assets.pipeline-element/inputs input-eids
                :arachne/instance-of {:db/ident :arachne.assets/Merge}}
        txdata [(util/mkeep tx-map)]]
    (cfg/resolve-tempid (script/transact txdata) tid)))