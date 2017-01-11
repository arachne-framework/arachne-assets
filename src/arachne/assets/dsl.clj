(ns arachne.assets.dsl
  (:refer-clojure :exclude [merge])
  (:require [arachne.core.config :as cfg]
            [arachne.core.util :as util]
            [arachne.core.config.init :as script :refer [defdsl]]
            [arachne.core.dsl.specs :as acs]
            [arachne.core.config.specs :as ccs]
            [clojure.spec :as s]
            [arachne.error :as e]
            [clojure.string :as str]))

(s/def ::dir string?)
(s/def ::watch? boolean?)

(s/def ::pattern (partial instance? java.util.regex.Pattern))

(s/def ::pattern-or-patterns
  (s/or :pattern ::pattern
    :patterns (s/coll-of ::pattern :min-count 1)))

(s/def ::include ::pattern-or-patterns)
(s/def ::exclude ::pattern-or-patterns)

(s/def ::input-opts (util/keys** :req-un [::dir]
                                 :opt-un [::watch?
                                          ::include
                                          ::exclude]))
(s/def ::ref (s/or :aid ::acs/id
                   :eid integer?
                   :tid #(instance? arachne.core.config.Tempid %)))

(defn- input
  "Define an asset pipeline input"
  [id opts classpath?]
  (let [[_ opts] (s/conform ::input-opts opts)
        entity (util/map-transform opts {:arachne/id id
                                         :arachne.component/constructor :arachne.assets.pipeline/input
                                         :arachne.assets.input/classpath? classpath?}
                 :dir     :arachne.assets.input/path identity
                 :watch?  :arachne.assets.input/watch? identity
                 :include :arachne.assets.input/include str
                 :exclude :arachne.assets.input/exclude str)]
    (script/transact [entity])))

(s/fdef input-dir
  :args (s/cat :arachne-id ::acs/id
               :opts ::input-opts))

(defdsl input-dir
  "Define a asset pipeline component that reads from a directory on the file
  system. The path maybe absolute or process-relative. Returns the entity ID of
  the component."
  [arachne-id & opts]
  (input arachne-id opts false))

(s/fdef input-resource
  :args (s/cat :arachne-id ::acs/id
               :opts ::input-opts))

(defdsl input-resource
  "Define a asset pipeline component that reads from a directory on the
  classpath. Returns the entity ID the component."
  [arachne-id & opts]
  (input arachne-id opts true))

(s/def ::output-opts (util/keys** :req-un [::dir]))

(s/fdef output-dir
  :args (s/cat :arachne-id ::acs/id
               :opts ::output-opts))

(defdsl output-dir
  "Define a asset pipeline component that writes to a directory on the file
  system."
  [arachne-id & opts]
  (let [[_ opts] (s/conform ::output-opts opts)
        entity {:arachne/id arachne-id
                :arachne.component/constructor :arachne.assets.pipeline/output
                :arachne.assets.output/path (:dir opts)}]
    (script/transact [entity])))

(s/def ::transformer ::ref)

(s/def ::transform-opts (util/keys** :req-un [::transformer]))

(s/fdef transform
  :args (s/cat :arachne-id ::acs/id
               :opts ::transform-opts))

(defn- resolve-ref
  "Given the conformed value of a ::ref spec, return correct txdata map"
  [[type id]]
  (case type
     :aid {:arachne/id id}
     :eid {:db/id id}
     :tid {:db/id id}))

(defdsl transform
  "Define a custom transformer asset pipeline component"
  [arachne-id & opts]
  (let [[_ opts] (s/conform ::transform-opts opts)
        entity {:arachne/id arachne-id
                :arachne.component/constructor :arachne.assets.pipeline/transform
                :arachne.assets.transform/transformer (resolve-ref (:transformer opts))
                :arachne.component/dependencies {:arachne.component.dependency/entity (resolve-ref (:transformer opts))
                                                 :arachne.component.dependency/key :transformer}}]
    (script/transact [entity])))

(defn- wire
  "Given the conformed ref of an input and output, return txdata that links them up."
  [input output]
  (let [entity (resolve-ref output)]
    (assoc entity :arachne.assets.consumer/inputs [(resolve-ref input)])))

(defn- implicit-merge
  "Given two conformed entity refs, return txdata for a pipeline component that merges them."
  [a b]
  {:db/id (cfg/tempid)
   :arachne/instance-of [:db/ident :arachne.assets/Merge]
   :arachne.component/constructor :arachne.assets.pipeline/merge
   :arachne.assets.consumer/inputs [(resolve-ref a) (resolve-ref b)]})


(s/def ::pipeline-tuple (s/tuple (s/or :ref ::ref
                                       :merge (s/coll-of ::ref :min-count 2))
                                 ::ref))

(s/def ::pipeline-tuples (s/coll-of ::pipeline-tuple :min-count 1))
(s/fdef pipeline :args ::pipeline-tuples)

(defdsl pipeline
  "Wire together pipeline elements into a directed graph. Takes a number of pair tuples, each item
   in the tuple must be either an Arachne ID or an entity ID.

  A simple tuple of the form [A B] indicates that A is an input of B.

  A nested tuple of the form [[A B] C] indicates that A and B should be merged, and the resulting
  pipeline element should be the input of B."
  [& tuples]
  (let [conformed (s/conform ::pipeline-tuples tuples)
        txdata (mapcat (fn [[[type input] output]]
                          (case type
                            :ref [(wire input output)]
                            :merge (let [m (apply implicit-merge input)
                                         tid-ref [:tid (:db/id m)]]
                                     [m (wire tid-ref output)])))
                 conformed)]
    (script/transact txdata)))