(ns arachne.assets.dsl
  (:refer-clojure :exclude [merge])
  (:require [arachne.core.config :as cfg]
            [arachne.core.util :as util]
            [arachne.core.config.init :as script :refer [defdsl]]
            [arachne.core.dsl.specs :as acs]
            [arachne.core.config.specs :as ccs]
            [clojure.spec :as s]
            [arachne.error :as e :refer [deferror error]]
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
(defn- input-dir*
  "Define an asset pipeline input directory"
  [id opts classpath?]
  (let [[_ opts] (s/conform ::input-opts opts)
        entity (util/map-transform opts {:arachne/id id
                                         :arachne.component/constructor :arachne.assets.pipeline/input-directory
                                         :arachne.assets.input-directory/classpath? classpath?}
                 :dir     :arachne.assets.input-directory/path identity
                 :watch?  :arachne.assets.input-directory/watch? identity
                 :include :arachne.assets.input-directory/include str
                 :exclude :arachne.assets.input-directory/exclude str)]
    (script/transact [entity])))

(s/fdef input-dir
  :args (s/cat :arachne-id ::acs/id
               :opts ::input-opts))

(defdsl input-dir
  "Define a asset pipeline component that reads from a directory on the file
  system. The path maybe absolute or process-relative. Returns the entity ID of
  the component."
  [arachne-id & opts]
  (input-dir* arachne-id opts false))

(s/fdef input-resource
  :args (s/cat :arachne-id ::acs/id
               :opts ::input-opts))

(defdsl input-resource
  "Define a asset pipeline component that reads from a directory on the
  classpath. Returns the entity ID the component."
  [arachne-id & opts]
  (input-dir* arachne-id opts true))

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
                :arachne.component/constructor :arachne.assets.pipeline/output-directory
                :arachne.assets.output-directory/path (:dir opts)}]
    (script/transact [entity])))

(s/def ::construtor (s/and symbol? namespace))
(s/def ::transducer-opts (util/keys** :req-un [::constructor]))

(s/fdef transducer
  :args (s/cat :arachne-id ::acs/id
               :opts ::transducer-opts))

(defdsl transducer
  "A pipeline element that applies a Clojure transducer filesets that pass through it"
  [arachne-id & opts]
  (let [[_ opts] (s/conform ::transducer-opts opts)
        entity {:arachne/id arachne-id
                :arachne.component/constructor :arachne.assets.pipeline/transducer
                :arachne.assets.transducer/constructor (keyword (:constructor opts))}]
    (script/transact [entity])))

(defn- wire
  "Given a tuple containing [producer-eid consumer-eid roles], return an entity map that links them up"
  [[producer consumer roles]]
  {:db/id consumer
   :arachne.assets.consumer/inputs [(util/mkeep {:arachne.assets.input/entity producer
                                                 :arachne.assets.input/roles roles})]})

(s/def ::ref (s/or :aid ::acs/id
                   :eid #(or (integer? %) (instance? arachne.core.config.Tempid %))))

(s/def ::roles (s/coll-of keyword? :kind set? :min-count 1))

(s/def ::pipeline-tuple (s/cat :producer ::ref :consumer ::ref :roles (s/? ::roles)))

(s/def ::pipeline-tuples (s/coll-of ::pipeline-tuple :min-count 1))
(s/fdef pipeline :args ::pipeline-tuples)

(defn- resolve-aid
  "Given the conformed value of a reference, assert that the entity actually exists in the context
   config, returning the entities eid."
  [[type id]]
  (case type
    :eid id
    :aid (script/resolve-aid id `pipeline)))

(defdsl pipeline
  "Wire together pipeline elements into a directed graph. Takes a number of pair tuples, each item
   in the tuple must be either an Arachne ID or an entity ID.

  A simple tuple of the form [A B] indicates that A is an input of B."
  [& tuples]
  (let [conformed (s/conform ::pipeline-tuples tuples)
        tuples (map (fn [{:keys [producer consumer roles]}]
                      [(resolve-aid producer) (resolve-aid consumer) roles]) conformed)
        txdata (map wire tuples)]
    (script/transact txdata)))
