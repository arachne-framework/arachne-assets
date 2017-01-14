(ns arachne.assets.pipeline
  (:refer-clojure :exclude [merge])
  (:require [arachne.fileset :as fs]
            [arachne.assets.config :as acfg]
            [arachne.assets.util :as autil]
            [com.stuartsierra.component :as c]
            [clojure.tools.logging :as log]
            [clojure.core.async :as a :refer [go go-loop >! <! <!! >!!]]
            [clojure.java.io :as io]
            [arachne.core.util :as util]
            [hawk.core :as hawk]
            [arachne.error :as e :refer [error deferror]])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defprotocol Transformer
  "A component that translates from multiple input channels to a single output channel."
  (-init [this ]
    "Given a fileset, return a transformed fileset."))

;; Note: To support a "pull" model for pipelines, we could simply add a second
;; channel that consumers would use to request a re-calculation.
(defprotocol Producer
  (-observe [this] "Return a channel upon which will be placed the initial and updated FileSet values.

  The returned channel will use a sliding buffer of size 1, so slow consumers of will not delay the producer."))

(defrecord WatchingInputDir [dist output-ch watcher]
  Producer
  (-observe [_] (a/tap dist (a/chan (a/sliding-buffer 1))))
  c/Lifecycle
  (start [this]
    (let [ch (a/chan)
          path (:arachne.assets.input-directory/path this)
          dir (io/file path)
          on-change (fn [evt ctx]
                      (log/debug "Watch triggered:" path)
                      (>!! ch (-> (fs/fileset) (fs/add dir)))
                      ctx)
          watcher (hawk/watch! [{:paths [path]
                                 :filter hawk/file?
                                 :handler on-change}])
          dist (autil/dist ch)]
      (on-change nil nil)
      (assoc this :output-ch ch :dist dist :watcher watcher)))
  (stop [this]
    (hawk/stop! watcher)
    (a/close! output-ch)
    (dissoc this :watcher :output-ch)))

(defrecord InputDir [dist output-ch]
  Producer
  (-observe [_] (a/tap dist (a/chan (a/sliding-buffer 1))))
  c/Lifecycle
  (start [this]
    (let [ch (a/chan)
          path (:arachne.assets.input-directory/path this)
          fs (fs/add (fs/fileset) (io/file path))
          dist (autil/dist ch)]
      (log/debug "Processing asset pipeline input:" path)
      (>!! ch fs)
      (assoc this :output-ch ch :dist dist)))
  (stop [this]
    (a/close! output-ch)
    (dissoc this :output-ch :dist)))

(defn input-channels
  "Given a Consumer component instance, calls `-observe` on each of the inputs and return a seq of [<name>
   <chan>] tuples for all the component's named inputs."
  [component]
  (map (fn [input-entity]
         (let [name (:arachne.assets.input/name input-entity)
               producer-eid (-> input-entity :arachne.assets.input/entity :db/id)
               producer (get component producer-eid)]
           [name (-observe producer)]))
    (:arachne.assets.consumer/inputs component)))

(defn merge-inputs
  "Given a collection of channels that emit filesets,return a single channel upon which will be
   placed the reuslt of merging *all* the inputs filesets, whenever *any* of them changes.

   Generally used for components that should accept multiple inputs, but do not make a
   distinction regarding which is which.

   This function will block until all of the inputs have returned at least one fileset, and the
   returned channel will already have one filset put on it.

   The output chan will close if any of the inputs closes."
  [inputs]
  (if (= 1 (count inputs))
    (first inputs)
    (let [output (a/chan (a/sliding-buffer 1))
          initial-vals (for [ch inputs]
                         [ch (<!! ch)])
          cache (atom (into {} initial-vals))
          new-fs (fn []
                   (apply fs/merge (vals @cache)))]
      (a/put! output (new-fs))
      (a/go-loop []
        (let [[fs ch] (a/alts! (keys @cache))]
          (if (nil? fs)
            (a/close! output)
            (do
              (swap! cache assoc ch fs)
              (>! output (new-fs))
              (recur)))))
      output)))

(defrecord OutputDir [dist]
  Producer
  (-observe [_] (a/tap dist (a/chan (a/sliding-buffer 1))))
  c/Lifecycle
  (start [this]
    (let [input-ch (merge-inputs (map second (input-channels this)))
          dist (autil/dist input-ch)
          path (:arachne.assets.output-directory/path this)
          output-file (io/file path)
          receive (fn [fs]
                    (when fs
                      (locking this
                        (log/debug "Processing asset pipeline output:" path)
                        (fs/commit! fs output-file))))
          receive-ch (a/tap dist (a/chan (a/sliding-buffer 1)))]
      (.mkdirs output-file)
      (receive (<!! receive-ch))
      (go (while (receive (<! receive-ch))))
      (assoc this :dist dist)))
  (stop [this] this))


(deferror ::transduce-failed
  :message "Transducer failed failed in asset Transducer with :eid (Arachne ID: :aid)."
  :explanation "An asset pipeline transducer with eid `:eid` and Arachne ID `:aid` threw an exception during pipeline processing.

  An empty fileset was passed to the next pipeline element."
  :suggestions ["Investigate the `cause` of this exception to determine what went wrong more specifically."]
  :ex-data-docs {:eid "The entity id of the transformer component"
                 :aid "The arachne ID of the transformer component"})

(defrecord Transducer [dist]
  Producer
  (-observe [_] (a/tap dist (a/chan (a/sliding-buffer 1))))
  c/Lifecycle
  (start [this]
    (let [xform-ctor (util/require-and-resolve (:arachne.assets.transducer/constructor this))
          xform (@xform-ctor this)
          ex-handler (fn [t]
                       (e/log-error ::transduce-failed
                         {:eid (:db/id this)
                          :aid (:arachne/id this)} t)
                       (fs/fileset))
          input-ch (merge-inputs (map second (input-channels this)))
          output-ch (a/chan (a/sliding-buffer 1) xform ex-handler)
          dist (autil/dist output-ch)]
      (a/go-loop []
        (if-let [v (<! input-ch)]
          (do
            (>! output-ch v)
            (recur))
          (a/close! output-ch)))
      (assoc this :dist dist)))
  (stop [this] this))

(defn input-directory
  "Constructor for an :arachne.assets/InputDirectory component"
  [entity]
  (if (:arachne.assets.input-directory/watch? entity)
    (map->WatchingInputDir {})
    (map->InputDir {})))

(defn output-directory
  "Constructor for an :arachne.assets/OutputDirectory component"
  [cfg eid]
  (map->OutputDir {}))

(defn transducer
  "Constructor for an :arachne.assets/Transducer component"
  [cfg eid]
  (map->Transducer {}))
