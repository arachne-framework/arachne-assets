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
  "A logical transformation on a FileSet"
  (-transform [this input]
    "Given a fileset, return a transformed fileset."))

(extend-protocol Transformer
  clojure.lang.Fn
  (-transform [f input] (f input)))


;; Note: To support a "pull" model for pipelines, we could simply add a second
;; channel that consumers would use to request a re-calculation.
(defprotocol PipelineElement
  (-observe [this ch] "Place the elements inital and updated FileSet values on
  the supplied channel, returning the channel"))

(defn- cache-dir
  "Find the cache dir to use for a given pipeline element"
  [cfg eid]
  (if-let [cache-path (acfg/find-cache-path cfg eid)]
    (io/file cache-path)
    (fs/default-cache-dir)))

(defrecord WatchingInput [dist output-ch watcher watching]
  PipelineElement
  (-observe [_ ch] (a/tap dist ch))
  c/Lifecycle
  (start [this]
    (reset! watching true)
    (let [ch (a/chan (a/sliding-buffer 1))
          cache (cache-dir (:arachne/config this) (:db/id this))
          path (:arachne.assets.input/path this)
          dir (io/file path)
          on-change (fn [evt ctx]
                      (log/debug "Watch triggered:" path)
                      (>!! ch (-> (fs/fileset cache) (fs/add dir)))
                      ctx)
          watcher (hawk/watch! [{:paths [path]
                                 :filter hawk/file?
                                 :handler on-change}])
          dist (autil/dist ch)]
      (on-change nil nil)
      (assoc this :output-ch ch :dist dist :watcher watcher)))
  (stop [this]
    (hawk/stop! watcher)
    (reset! watching false)
    (a/close! output-ch)
    (dissoc this :watcher :output-ch)))

(defrecord Input [dist output-ch]
  PipelineElement
  (-observe [_ ch] (a/tap dist ch))
  c/Lifecycle
  (start [this]
    (let [ch (a/chan)
          cache (cache-dir (:arachne/config this) (:db/id this))
          path (:arachne.assets.input/path this)
          fs1 (fs/fileset cache)
          fs (fs/add fs1 (io/file path))]
      (log/debug "Processing asset pipeline input:" path)
      (go (>! ch fs))
      (assoc this :output-ch ch :dist (autil/dist ch))))
  (stop [this]
    (a/close! output-ch)
    (dissoc this :output-ch :dist)))

(defrecord Output [input dist running]
  PipelineElement
  (-observe [_ ch] (a/tap dist ch))
  c/Lifecycle
  (start [this]
    (reset! running true)
    (let [dist (autil/dist (-observe input (a/chan)))
          ch (a/tap dist (a/chan))
          path (:arachne.assets.output/path this)
          output-file (io/file path)
          recieve (fn [fs]
                    (when fs
                      (locking this
                        (log/debug "Processing asset pipeline output:" path)
                        (fs/commit! fs output-file))))]
      (.mkdirs output-file)
      (recieve (<!! ch))
      (go (while @running (recieve (<! ch)))
          (a/close! ch)))
    (assoc this :dist dist))
  (stop [this]
    (reset! running false)
    this))

(deferror ::transform-failed
  :message "Transformation failed for Transform :eid (Arachne ID: :aid)."
  :explanation "A transform component threw an exception while invoking its `-transform` method. An empty fileset was passed to the next pipeline element."
  :suggestions ["Investigate the `cause` of this exception to determine what went wrong more specifically."]
  :ex-data-docs {:eid "The entity id of the transformer component"
                 :aid "The arachne ID of the transformer component"})

(defrecord Transform [input transformer running dist]
  PipelineElement
  (-observe [_ ch] (a/tap dist ch))
  c/Lifecycle
  (start [this]
    (reset! running true)
    (let [input-ch (-observe input (a/chan))
          output-ch (a/chan)
          dist (autil/dist output-ch)
          xform (fn [fs]
                  (locking this
                    (try
                      (-transform transformer fs)
                      (catch Throwable t
                        (e/log-error ::transform-failed
                          {:eid (:db/id this)
                           :aid (:arachne/id this)} t)
                        (fs/empty fs)))))]
      (go-loop []
        (when-let [fs (<! input-ch)]
          (>! output-ch (xform fs)))
        (if @running
          (recur)
          (a/close! output-ch)))
      (assoc this :dist dist)))
  (stop [this]
    (reset! running false)
    this))

(defn- find-merge-inputs
  "Return a Merge component's input components"
  [c]
  (->> c
    :arachne.assets.pipeline-element/inputs
    (map :db/id)
    (map #(get c %))))

(defrecord Merge [running dist]
  PipelineElement
  (-observe [_ ch] (a/tap dist ch))
  c/Lifecycle
  (start [this]
    (reset! running true)
    ;; Each source must provide at least one value before a single value is put
    ;; on the output channel (before it can even start).
    ;; From then on, whenever *any* source is updated, a merge value is
    ;; calculated using the most recent values of each of the other sources, and
    ;; put on the output channel.
    (let [output-ch (a/chan (a/sliding-buffer 1))
          dist (autil/dist output-ch)
          input-channels (map #(let [ch (a/chan)] (-observe % ch))
                           (find-merge-inputs this))
          cache (atom (into {}
                        (map (fn [ch] [ch (<!! ch)])
                          input-channels)))
          new-fs (fn []
                   (apply fs/merge (vals @cache)))]
      (a/put! output-ch (new-fs))
      (a/go-loop []
        (let [[fs ch] (a/alts! (keys @cache))]
          (if (nil? fs)
            (a/close! output-ch)
            (do
              (swap! cache assoc ch fs)
              (>! output-ch (new-fs))
              (when @running (recur))))))
      (assoc this :dist dist)))
  (stop [this]
    (reset! running false)
    this))

(defn input
  "Constructor for an :arachne.assets/Input component"
  [entity]
  (if (:arachne.assets.input/watch? entity)
    (map->WatchingInput {:watching (atom false)})
    (map->Input {})))

(defn output
  "Constructor for an :arachne.assets/Output component"
  [cfg eid]
  (map->Output {:running (atom false)}))

(defn transform
  "Constructor for an :arachne.assets/Transform component"
  [cfg eid]
  (map->Transform {:running (atom false)}))

(defn merge
  "Constructor for an :arachne.assets/Merge component"
  [cfg eid]
  (map->Merge {:running (atom false)}))