(ns arachne.assets.pipeline
  (:refer-clojure :exclude [merge])
  (:require [arachne.fileset :as fs]
            [arachne.assets.config :as acfg]
            [arachne.assets.util :as autil]
            [com.stuartsierra.component :as c]
            [arachne.log :as log]
            [clojure.core.async :as a :refer [go go-loop >! <! <!! >!!]]
            [clojure.java.io :as io]
            [arachne.core.util :as util]
            [hawk.core :as hawk]
            [arachne.error :as e :refer [error deferror]]
            [clojure.string :as str])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.net URI]))

(defprotocol Transformer
  "A component that translates from multiple input channels to a single output channel."
  (-init [this ]
    "Given a fileset, return a transformed fileset."))

;; Note: To support a "pull" model for pipelines, we could simply add a second
;; channel that consumers would use to request a re-calculation.
(defprotocol Producer
  (-observe [this] "Return a channel upon which will be placed the initial and updated FileSet values.

  The returned channel will use a sliding buffer of size 1, so slow consumers of will not delay the producer."))

(defn watch-dir
  "Given a directory path, return a tuple of [terminatefn- chan]. Immediately, and then
   whenever the contents of the directory change, a fileset will be placed on the channel until the
   terminate function is called."
  [dir]
  (let [path (.getPath dir)
        ch (a/chan (a/sliding-buffer 1))
        watcher-atom (atom nil)
        on-change (fn [evt ctx]
                    (log/debug :msg "Change in watched directory" :path path)
                    (when-not (>!! ch (fs/add (fs/fileset) dir))
                      (log/debug :msg "Terminating directory watch due to closed channel" :path path)
                      (when-let [watcher @watcher-atom]
                        (hawk/stop! watcher)))
                    ctx)]
    (reset! watcher-atom (hawk/watch! [{:paths [path]
                                        :filter hawk/file?
                                        :handler on-change}]))
    (on-change nil nil)
    ch))

(defrecord WatchingInputDir [dist output-ch terminate-fn]
  Producer
  (-observe [_] (a/tap dist (a/chan (a/sliding-buffer 1))))
  c/Lifecycle
  (start [this]
    (let [path (:arachne.assets.input-directory/path this)
          file (if (:arachne.assets.input-directory/classpath? this)
                 (io/file (io/resource path))
                 (io/file path))
          ch (watch-dir file)
          dist (autil/dist ch)]
      (assoc this :output-ch ch :dist dist :terminate-fn terminate-fn)))
  (stop [this]
    (a/close! output-ch)
    (dissoc this :watcher :output-ch)))

(defrecord InputDir [dist output-ch]
  Producer
  (-observe [_] (a/tap dist (a/chan (a/sliding-buffer 1))))
  c/Lifecycle
  (start [this]
    (let [ch (a/chan)
          path (:arachne.assets.input-directory/path this)
          file (if (:arachne.assets.input-directory/classpath? this)
                 (io/file (io/resource path))
                 (io/file path))
          fs (fs/add (fs/fileset) file)
          dist (autil/dist ch)]
      (log/debug :msg "Processing asset input dir" :path path)
      (>!! ch fs)
      (assoc this :output-ch ch :dist dist)))
  (stop [this]
    (a/close! output-ch)
    (dissoc this :output-ch :dist)))


(defn input-directory
  "Constructor for an :arachne.assets/InputDirectory component"
  [entity]
  (if (:arachne.assets.input-directory/watch? entity)
    (map->WatchingInputDir {})
    (map->InputDir {})))

(defn input-channels
  "Given a Consumer component instance, calls `-observe` on each of the inputs and return a seq of
   [<chan> <roles>] tuples for all the component's inputs."
  [component]
  (map (fn [input-entity]
         (let [roles (:arachne.assets.input/roles input-entity)
               producer-eid (-> input-entity :arachne.assets.input/entity :db/id)
               producer (get component producer-eid)]
           [(-observe producer) roles]))
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

(defrecord OutputDir []
  c/Lifecycle
  (start [this]
    (let [input-ch (merge-inputs (map first (input-channels this)))
          path (:arachne.assets.output-directory/path this)
          output-file (io/file path)
          receive (fn [fs]
                    (when fs
                      (locking this
                        (log/debug :msg "Procesing asset output dir" :path path)
                        (fs/commit! fs output-file))))]
      (.mkdirs output-file)
      (receive (<!! input-ch))
      (go (while (receive (<! input-ch))))
      this))
  (stop [this] this))


(defn output-directory
  "Constructor for an :arachne.assets/OutputDirectory component"
  [cfg eid]
  (map->OutputDir {}))


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
          input-ch (merge-inputs (map first (input-channels this)))
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

(defn transducer
  "Constructor for an :arachne.assets/Transducer component"
  [cfg eid]
  (map->Transducer {}))

(defprotocol FSView
  "A pipeline component that presents a view to the current fileset"
  (find-file [this path] "Returns a java.io.File object for the file at the given path in the fileset, or nil if such a file does not exist. The returned file is immutable."))

(defrecord FSViewComponent [state]
  FSView
  (find-file [this path] (fs/file @state path))
  c/Lifecycle
  (start [this]
    (let [input-ch (merge-inputs (map first (input-channels this)))
          state (atom (<!! input-ch))]
      (go-loop []
        (when-let [fs (<! input-ch)]
          (reset! state fs)
          (recur)))
      (assoc this :state state)))
  (stop [this] (dissoc this :state)))

(defn fileset-view
  "Constructor for a FSView component"
  []
  (map->FSViewComponent {}))