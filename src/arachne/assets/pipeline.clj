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
           [java.net URI]
           (java.nio.file FileSystem FileSystems Files Paths CopyOption OpenOption)))

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
  "Given a directory path, return a channel. Immediately, and thenwhenever the contents of the
   directory change, a fileset will be placed on the channel until the terminate function is
   called. Optionally, a channel to use may be passed in."
  ([dir] (watch-dir dir (a/chan (a/sliding-buffer 1))))
  ([dir ch]
   (let [path (.getPath dir)
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
     ch)))

(deferror ::cannot-watch-jar
  :message "Cannot watch path in JAR file for pipeline input `:eid` (Arachne ID: `:aid`)"
  :explanation "An input component with entity ID `:eid` and Arachne ID `:aid` was instructed to watch the directory `:path` on the classpath for any changes.

   However, that classpath location resolved to `:url-path`, which is in a JAR file.

   Jar files cannot be watched (nor would it be meaningful to do so, as their contents cannot change at runtime.)"
  :suggestions ["Set `:watch?` to false for this input component"
                "Watch a directory that is not in a JAR file"]
  :ex-data-docs {:eid "The entity id of the input"
                 :aid "The arachne ID of the input"
                 :path "The path specified in the config"
                 :url-path "The actual resolved path"})

(deferror ::cannot-watch-multiple-paths
  :message "Cannot watch multiple concrete directories on classpath for pipeline input `:eid` (Arachne ID: `:aid`)"
  :explanation "An input component with entity ID `:eid` and Arachne ID `:aid` was instructed to watch the directory `:path` on the classpath for any changes.

   However, that path resolves to :urls-count locations on the classpath.

   Arachne does not currently support classpath inputs that attempt to watch multiple directories at the same time."
  :suggestions ["Set `:watch?` to false for this input component"
                "Watch a different path that resolves to only one directory."]
  :ex-data-docs {:eid "The entity id of the input"
                 :aid "The arachne ID of the input"
                 :path "The path specified in the config"
                 :urls "The URLs that were resolved"
                 :url-count "How many classpath resources were found at the path"})

(deferror ::path-not-found
  :message "Path `:path` not found"
  :explanation "An input component with entity ID `:eid` and Arachne ID `:aid` was specified to load resources from `:path`.

   The component attempted to find the directory :location. However, no such diretory could be found."
  :suggestions ["Ensure that the input component refers to an existing directory, with no typos"]
  :ex-data-docs {:eid "The entity id of the input"
                 :aid "The arachne ID of the input"
                 :path "The path specified in the config"})

(defn- jar-url?
  [url]
  (= "jar" (.getProtocol url)))

(defn- resources
  "Return a seq of all the URLS on the classpath at a particular path"
  [path]
  (enumeration-seq (.getResources (.getContextClassLoader (Thread/currentThread)) path)))

(defrecord WatchingInputDir [dist output-ch terminate-fn]
  Producer
  (-observe [_] (a/tap dist (a/chan (a/sliding-buffer 1))))
  c/Lifecycle
  (start [this]
    (let [path (:arachne.assets.input-directory/path this)
          file (if (:arachne.assets.input-directory/classpath? this)
                 (let [urls (resources path)]
                   (when (empty? urls)
                     (error ::path-not-found {:aid (:arachne/id this)
                                              :eid (:db/id this)
                                              :path path
                                              :location "on the classpath"}))
                   (when (not= 1 (count urls))
                     (error ::cannot-watch-multiple-paths {:aid (:arachne/id this)
                                                           :eid (:db/id this)
                                                           :path path
                                                           :urls urls
                                                           :urls-count (count urls)}))
                   (let [url (first urls)]
                     (when (jar-url? url) (error ::cannot-watch-jar {:aid (:arachne/id this)
                                                                     :eid (:db/id this)
                                                                     :path path
                                                                     :url-path (.getPath url)}))
                     (io/file url)))
                 (io/file path))
          ch (watch-dir file)
          dist (autil/dist ch)]
      (assoc this :output-ch ch :dist dist :terminate-fn terminate-fn)))
  (stop [this]
    (a/close! output-ch)
    (dissoc this :watcher :output-ch)))

(defn- classpath-fileset
  "Load a fileset from the classpath (including a JAR).

  If there are multiple matching paths, they will be merged into a single fileset."
  [component path]
  (let [urls (resources path)]
    (when (empty? urls)
      (error ::path-not-found {:aid (:arachne/id component)
                               :eid (:db/id component)
                               :path path
                               :location "on the classpath"}))
    (reduce (fn [fileset url]
              (if (jar-url? url)
                (with-open [filesystem (FileSystems/newFileSystem (.toURI url) {})]
                  (fs/add fileset (.toAbsolutePath (.getPath filesystem path (into-array String [])))))
                (fs/add fileset (io/file url))))
      (fs/fileset) urls)))

(defrecord InputDir [dist output-ch]
  Producer
  (-observe [_] (a/tap dist (a/chan (a/sliding-buffer 1))))
  c/Lifecycle
  (start [this]
    (let [ch (a/chan)
          path (:arachne.assets.input-directory/path this)
          fs (if (:arachne.assets.input-directory/classpath? this)
                 (classpath-fileset this path)
                 (if-let [f (io/file path)]
                   (fs/add (fs/fileset) f)
                   (error ::path-not-found {:aid (:arachne/id this)
                                            :eid (:db/id this)
                                            :path path
                                            :location "relative to the process directory"})))
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

(defrecord FSViewComponent [state inputs]
  FSView
  (find-file [this path] (fs/file @state path))
  c/Lifecycle
  (start [this]
    (let [inputs (or inputs (map first (input-channels this)))
          input-ch (merge-inputs inputs)
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