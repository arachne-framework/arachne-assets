(ns arachne.assets.arachne-fileset
  (:require [clojure.tools.logging :as log]
            [boot.tmpdir :as tmpd]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def tmp-registry (atom #{}))

(defn delete!
  "Recursively delete a directory and its contents"
  [f]
  (when (.isDirectory f)
    (doseq [child (seq (.list f))] (delete! child)))
  (.delete f))

(.addShutdownHook (Runtime/getRuntime)
  (Thread. (fn []
             (log/debug "cleaning up temp directories")
             (dorun (map delete! @tmp-registry)))))

(defn tmpdir!
  "Return a new temporary directory as a java.io.File. The directory will be in
  the system temporary directory, and marked for deletion when the JVM
  terminates.

  Files created using the same key in the same process will resolve to the same
  directory"
  []
  (let [f (.toFile (Files/createTempDirectory "arachne"
                     (make-array FileAttribute 0)))]
    (log/debug "Creating temp directory at " (.getPath f))
    (swap! tmp-registry conj f)
    f))
