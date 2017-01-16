(ns arachne.assets.dsl
  (:refer-clojure :exclude [merge])
  (:require [arachne.core.config :as cfg]
            [arachne.core.util :as util]
            [arachne.core.config.script :as script :refer [defdsl]]
            [arachne.core.config.specs :as ccs]
            [clojure.spec :as s]
            [arachne.error :as e :refer [deferror error]]
            [clojure.string :as str]
            [arachne.core.dsl :as core]))

(s/def ::dir string?)
(s/def ::watch? boolean?)
(s/def ::classpath? boolean?)

(s/def ::input-opts (util/keys** :opt-un [::watch?
                                          ;; TODO: implement include/exclude
                                          ;; ::include
                                          ;; ::exclude
                                          ::classpath?]))

(defdsl input-dir
  "Define a asset producer component that reads from a directory on the file
  system. The path maybe absolute or process-relative. Returns the entity ID of
  the component.

  Arguments are:

  - arachne-id (optional): the Arachne ID of the component
  - dir (mandatory): the directory to read from
  - opts (optional): map (or kwargs) of additional options

  Options currently supported are:

  - :watch? - should the input watch for changes in the directory? Defaults to false.
  - :classpath? - true if the given directory is relative to the current classpath, rather than the current project

  Returns the entity ID of the newly created component."
  (s/cat :arachne-id (s/? ::core/arachne-id)
         :dir ::dir
         :opts ::input-opts)
  [<arachne-id> dir & opts]
  (let [tid (cfg/tempid)
        entity (util/mkeep {:db/id tid
                            :arachne/id (:arachne-id &args)
                            :arachne.component/constructor :arachne.assets.pipeline/input-directory
                            :arachne.assets.input-directory/path (:dir &args)
                            :arachne.assets.input-directory/classpath? (-> &args :opts second :classpath?)
                            :arachne.assets.input-directory/watch? (-> &args :opts second :watch?)})]
    (script/transact [entity] tid)))

(defdsl output-dir
  "Define a asset consumer component that writes to a directory on the file system.

  Arguments are:

  - arachne-id (optional): The Arachne ID of the component
  - dir (mandatory): The directory of the component

  Returns the entity ID of the newly created component."
  (s/cat :arachne-id (s/? ::core/arachne-id)
         :dir ::dir)
  [<arachne-id> dir]
  (let [tid (cfg/tempid)
        entity (util/mkeep {:db/id tid
                            :arachne/id (:arachne-id &args)
                            :arachne.component/constructor :arachne.assets.pipeline/output-directory
                            :arachne.assets.output-directory/path (:dir &args)})]
    (script/transact [entity] tid)))

(s/def ::constructor (s/and symbol? namespace))

(defdsl transducer
  "Define an asset consumer/producer component that applies a Clojure transducer filesets that pass through it.

  Arguments are:

  - arachne-id (optional): The Arachne ID of the component
  - ctor (mandatory): the fully-qualified symbol of a function that, when invoked and passed the
    initialized component instance will return a transducer over FileSets.

    Returns the entity ID of the newly created component."
  (s/cat :arachne-id (s/? ::core/arachne-id)
         :constructor ::constructor)
  [<arachne-id> ctor]
  (let [tid (cfg/tempid)
        entity (util/mkeep
                 {:db/id tid
                  :arachne/id (:arachne-id &args)
                  :arachne.component/constructor :arachne.assets.pipeline/transducer
                  :arachne.assets.transducer/constructor (keyword (:constructor &args))})]
    (script/transact [entity] tid)))

(defn- wire
  "Given a tuple containing [producer-eid consumer-eid roles], return an entity map that links them up"
  [[producer consumer roles]]
  {:db/id consumer
   :arachne.assets.consumer/inputs [(util/mkeep {:arachne.assets.input/entity producer
                                                 :arachne.assets.input/roles roles})]})

(s/def ::roles (s/coll-of keyword? :min-count 1))

(s/def ::pipeline-tuple (s/cat :producer ::core/ref :consumer ::core/ref :roles (s/? ::roles)))

(defdsl pipeline
  "Wire together asset pipeline components into a directed graph, structuring the flow of assets through the pipeline.

   The arguments are any number of tuples. The structure of each tuple is:

   [<producer> <consumer> <roles?>]

   <producer> and <consumer> may be Arachne IDs or entity IDs.

   In every tuple, <producer> and <consumer> are mandatory, <roles> is optional.

   Each tuple establishes a producer/consumer relationship between the pipeline components, and
   (if roles are present) specifies the role of the producer to the consumer, which is required by
   some consumers."
  (s/coll-of ::pipeline-tuple :min-count 1)
  [& tuples]
  (let [tuples (map (fn [{:keys [producer consumer roles]}]
                      [(core/resolved-ref producer) (core/resolved-ref consumer) (set roles)])
                 &args)
        txdata (map wire tuples)]
    (script/transact txdata)))