(ns arachne.assets-test
  (:require [clojure.test :refer :all]
            [arachne.core :as core]
            [arachne.assets :as assets]
            [arachne.fileset :as fs]
            [arachne.fileset.tmpdir :as tmpdir]
            [com.stuartsierra.component :as c]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io FileNotFoundException]))

(defn input-output-cfg-script
  [output-path]
  `(do
     (require '[arachne.core.dsl :as ~'core])
     (require '[arachne.assets.dsl :as ~'a])

     (~'core/runtime :test/rt [:test/output])

     (a/input-dir :test/input "test/test-assets")
     (a/output-dir :test/output :test/input ~output-path)))

(deftest input-output-test
  (let [output-dir (tmpdir/tmpdir!)
        script (input-output-cfg-script (.getPath output-dir))
        cfg (core/build-config [:org.arachne-framework/arachne-assets] script)
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)]
    (is (= #{"file1.md" "file2.md" "file3.md"}
          (->> (file-seq output-dir)
            (filter #(.isFile %))
            (map #(.getName %))
            (set))))
    (c/stop rt)))

(defn watcher-script
  [input-path output-path]
  `(do
     (require '[arachne.core.dsl :as ~'core])
     (require '[arachne.assets.dsl :as ~'a])

     (~'core/runtime :test/rt [:test/output])

     (a/input-dir :test/input ~input-path :watch? true)
     (a/output-dir :test/output :test/input ~output-path)))

(defn- waitfor
  "Wait until a predicate returns truth, with a timeout. Returns true if it ever
  became successful, otherwise false."
  [timeout pred]
  (let [started (System/currentTimeMillis)]
    @(future
       (loop []
         (if (pred)
           true
           (let [elapsed (- (System/currentTimeMillis) started)]
             (if (< elapsed timeout)
               (recur)
               false)))))))

(defn- maybe-slurp
  [v]
  (try
    (slurp v)
    (catch FileNotFoundException e
      nil)))

(deftest watcher-test
  (let [input-dir (tmpdir/tmpdir!)
        input-file (io/file input-dir "file.md")
        output-dir (tmpdir/tmpdir!)
        output-file (io/file output-dir "file.md")
        script (watcher-script (.getPath input-dir) (.getPath output-dir))
        cfg (core/build-config [:org.arachne-framework/arachne-assets] script)
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)]
    (spit input-file "INITIAL VALUE")
    (is (waitfor 1000 #(= "INITIAL VALUE" (maybe-slurp output-file))))
    ;; FS watcher won't pick up a second change in quick succession...
    (Thread/sleep 1000)
    (spit input-file "UPDATED VALUE")
    (is (waitfor 1000 #(= "UPDATED VALUE" (maybe-slurp output-file))))
    (c/stop rt)))


(defn fork-script
  [output-path-a output-path-b]
  `(do
     (require '[arachne.core.dsl :as ~'core])
     (require '[arachne.assets.dsl :as ~'a])

     (~'core/runtime :test/rt [:test/output-a :test/output-b])

     (a/input-dir :test/input "test/test-assets")
     (a/output-dir :test/output-a :test/input ~output-path-a)
     (a/output-dir :test/output-b :test/input ~output-path-b)))

(deftest fork-test
  (let [output-dir-a (tmpdir/tmpdir!)
        output-dir-b (tmpdir/tmpdir!)
        script (fork-script
                 (.getPath output-dir-a)
                 (.getPath output-dir-b))
        cfg (core/build-config [:org.arachne-framework/arachne-assets] script)
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)]
    (is (= 3 (->> (file-seq output-dir-a)
               (filter #(.isFile %))
               (count))))
    (is (= 3 (->> (file-seq output-dir-b)
               (filter #(.isFile %))
               (count))))
    (c/stop rt)))

(defn test-transformer
  []
  (let [working-dir (tmpdir/tmpdir!)]
    (fn [fs]
      (fs/commit! fs working-dir)

      ;; Imperatively update working dir
      (doseq [f (file-seq working-dir)]
        (when (re-find #"\.md$" (str f))
          (let [new-path (str/replace (str f) #"\.md$" ".out")]
            (spit new-path
              (str/upper-case (slurp f))))))

      (fs/add (fs/empty fs) working-dir :include [#".*\.out"]))))

(defn transform-script
  [output-path]
  `(do
     (require '[arachne.core.dsl :as ~'core])
     (require '[arachne.assets.dsl :as ~'a])

     (~'core/runtime :test/rt [:test/output])

     (~'core/component :test/test-transform {}
       'arachne.assets-test/test-transformer)

     (a/input-dir :test/input "test/test-assets")
     (a/transform :test/xform :test/input :test/test-transform)
     (a/output-dir :test/output :test/xform ~output-path)))

(deftest transform-test
  (let [output-dir (tmpdir/tmpdir!)
        script (transform-script (.getPath output-dir))
        cfg (core/build-config [:org.arachne-framework/arachne-assets] script)
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)]
    (is (= 3 (->> (file-seq output-dir)
               (filter #(re-find #"\.out$" (str %)))
               (count))))
    (is (= "THIS IS A FILE" (slurp (io/file output-dir "file1.out"))))
    (c/stop rt)))

(comment

  (def output-dir (tmpdir/tmpdir!))
  (def script (transform-script (.getPath output-dir)))
  (def cfg (core/build-config [:org.arachne-framework/arachne-assets] script))
  (def rt (core/runtime cfg :test/rt))
  (def rt' (c/start rt))


  (defrecord TA []
    c/Lifecycle
    (start [this] (println "starting TA") this)
    (stop [this] (println "stopping TA") this))

  (defrecord TB [ta]
    c/Lifecycle
    (start [t] (println "starting TB, ta:" ta) t)
    (stop [t] (println "stopping TB") t))

  (defrecord TC [tb]
    c/Lifecycle
    (start [t] (println "starting TC, tb:" tb) t)
    (stop [t] (println "stopping TC") t))

  (defn ta []
    (println "instantiating ta")
    (java.util.Date.))

  (defn tb []
    (println "instantiating tb")
    {})

  (defn tc []
    (println "instantiating tc")
    (->TC nil))

  (def sys (c/system-map
                :ta (ta)
                :tb (tb)
                :tc (tc)))

  (def sys' (c/system-using sys
              {:tb [:ta]
               :tc [:tb]}))

  (def sys'' (c/start sys'))





  )

(defn merge-script
  [input-a-path input-b-path output-path]
  `(do
     (require '[arachne.core.dsl :as ~'core])
     (require '[arachne.assets.dsl :as ~'a])

     (~'core/runtime :test/rt [:test/output])

     (a/input-dir :test/input-a ~input-a-path)
     (a/input-dir :test/input-b ~input-b-path)
     (a/merge :test/merge [:test/input-a :test/input-b])
     (a/output-dir :test/output :test/merge ~output-path)))

(deftest merge-test
  (let [output-dir (tmpdir/tmpdir!)
        input-a-dir (tmpdir/tmpdir!)
        input-b-dir (tmpdir/tmpdir!)
        _ (spit (io/file input-a-dir "file1.md") "file1")
        _ (spit (io/file input-b-dir "file2.md") "file2")
        _ (spit (io/file input-a-dir "file3.md") "file3")
        _ (spit (io/file input-b-dir "file3.md") "file3")
        script (merge-script
                 (.getPath input-a-dir)
                 (.getPath input-b-dir)
                 (.getPath output-dir))
        cfg (core/build-config [:org.arachne-framework/arachne-assets] script)
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)]
    (is (= 3 (->> (file-seq output-dir)
               (filter #(re-find #"\.md" (str %)))
               (count))))
    (is (= "file1" (slurp (io/file output-dir "file1.md"))))
    (is (= "file2" (slurp (io/file output-dir "file2.md"))))
    (is (= "file3" (slurp (io/file output-dir "file3.md"))))

    (c/stop rt)))
