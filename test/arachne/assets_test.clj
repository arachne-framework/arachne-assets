(ns arachne.assets-test
  (:require [clojure.test :refer :all]
            [arachne.core :as core]
            [arachne.core.config :as cfg]
            [arachne.assets :as assets]
            [arachne.fileset :as fs]
            [arachne.fileset.tmpdir :as tmpdir]
            [com.stuartsierra.component :as c]
            [clojure.string :as str]
            [clojure.java.io :as io]

            [arachne.core.dsl :as a]
            [arachne.assets.dsl :as aa])
  (:import [java.io FileNotFoundException]))

(defn input-output-cfg
  [output-path]

  (a/runtime :test/rt [:test/output])

  (aa/input-dir :test/input :dir "test/test-assets")
  (aa/output-dir :test/output :dir output-path)

  (aa/pipeline [:test/input :test/output]))

(deftest input-output-test
  (let [output-dir (.getCanonicalPath (tmpdir/tmpdir!))
        cfg (core/build-config [:org.arachne-framework/arachne-assets]
              `(arachne.assets-test/input-output-cfg ~output-dir))
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)]
    (is (= #{"file1.md" "file2.md" "file3.md"}
          (->> (file-seq (io/file output-dir))
            (filter #(.isFile %))
            (map #(.getName %))
            (set))))
    (c/stop rt)))

(defn watcher-cfg
  [input-path output-path]

  (a/runtime :test/rt [:test/output])

  (aa/input-dir :test/input :dir input-path :watch? true)
  (aa/output-dir :test/output :dir output-path)

  (aa/pipeline [:test/input :test/output]))

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
        cfg (core/build-config [:org.arachne-framework/arachne-assets]
              `(arachne.assets-test/watcher-cfg ~(.getPath input-dir) ~(.getPath output-dir)))
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)]
    (spit input-file "INITIAL VALUE")
    (is (waitfor 1000 #(= "INITIAL VALUE" (maybe-slurp output-file))))
    ;; FS watcher won't pick up a second change in quick succession...
    (Thread/sleep 1000)
    (spit input-file "UPDATED VALUE")
    (is (waitfor 1000 #(= "UPDATED VALUE" (maybe-slurp output-file))))
    (c/stop rt)))


(defn fork-cfg
  [output-path-a output-path-b]

  (a/runtime :test/rt [:test/output-a :test/output-b])

  (aa/input-dir :test/input :dir "test/test-assets")
  (aa/output-dir :test/output-a :dir output-path-a)
  (aa/output-dir :test/output-b :dir output-path-b)

  (aa/pipeline
    [:test/input :test/output-a]
    [:test/input :test/output-b]))

(deftest fork-test
  (let [output-dir-a (tmpdir/tmpdir!)
        output-dir-b (tmpdir/tmpdir!)
        cfg (core/build-config [:org.arachne-framework/arachne-assets]
              `(arachne.assets-test/fork-cfg ~(.getPath output-dir-a)
                                             ~(.getPath output-dir-b)))
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)]
    (is (= 3 (->> (file-seq output-dir-a)
               (filter #(.isFile %))
               (count))))
    (is (= 3 (->> (file-seq output-dir-b)
               (filter #(.isFile %))
               (count))))
    (c/stop rt)))

(defn transducer-ctor
  [transducer]
  (let [working-dir (tmpdir/tmpdir!)]
    (map (fn [fs]
           (fs/commit! fs working-dir)

           ;; Imperatively update working dir
           (doseq [f (file-seq working-dir)]
             (when (re-find #"\.md$" (str f))
               (let [new-path (str/replace (str f) #"\.md$" ".out")]
                 (spit new-path
                   (str/upper-case (slurp f))))))

           (fs/add (fs/empty fs) working-dir :include [#".*\.out"])))))

(defn transducer-cfg
  [output-path]

  (a/runtime :test/rt [:test/output])

  (a/component :test/test-transform {} `arachne.assets-test/test-transformer)

  (aa/input-dir :test/input :dir "test/test-assets")
  (aa/transducer :test/xform :constructor 'arachne.assets-test/transducer-ctor)
  (aa/output-dir :test/output :dir output-path)

  (aa/pipeline
    [:test/input :test/xform]
    [:test/xform :test/output]))

(deftest transducer-test
  (let [output-dir (tmpdir/tmpdir!)
        cfg (core/build-config [:org.arachne-framework/arachne-assets]
              `(arachne.assets-test/transducer-cfg ~(.getPath output-dir)))
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)]
    (is (= 3 (->> (file-seq output-dir)
               (filter #(re-find #"\.out$" (str %)))
               (count))))
    (is (= "THIS IS A FILE" (slurp (io/file output-dir "file1.out"))))
    (c/stop rt)))

(defn merge-cfg
  [input-a-path input-b-path output-path]

  (a/runtime :test/rt [:test/output])

  (aa/input-dir :test/input-a :dir input-a-path)
  (aa/input-dir :test/input-b :dir input-b-path)
  (aa/output-dir :test/output :dir output-path)

  (aa/pipeline [:test/input-a :test/output #{:role-1}]
               [:test/input-b :test/output #{:role-2}]))

(deftest merge-test
  (let [output-dir (tmpdir/tmpdir!)
        input-a-dir (tmpdir/tmpdir!)
        input-b-dir (tmpdir/tmpdir!)
        _ (spit (io/file input-a-dir "file1.md") "file1")
        _ (spit (io/file input-b-dir "file2.md") "file2")
        _ (spit (io/file input-a-dir "file3.md") "file3")
        _ (spit (io/file input-b-dir "file3.md") "file3")
        cfg (core/build-config [:org.arachne-framework/arachne-assets]
              `(arachne.assets-test/merge-cfg ~(.getPath input-a-dir)
                                              ~(.getPath input-b-dir)
                                              ~(.getPath output-dir)))
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)]

    (testing "roles are persisted in config"
      (is (= #{:role-1 :role-2}
            (set (cfg/q cfg '[:find [?r ...]
                              :where
                              [?o :arachne/id :test/output]
                              [?o :arachne.assets.consumer/inputs ?i]
                              [?i :arachne.assets.input/roles ?r]])))))

    (is (= 3 (->> (file-seq output-dir)
               (filter #(re-find #"\.md" (str %)))
               (count))))
    (is (= "file1" (slurp (io/file output-dir "file1.md"))))
    (is (= "file2" (slurp (io/file output-dir "file2.md"))))
    (is (= "file3" (slurp (io/file output-dir "file3.md"))))

    (c/stop rt)))
