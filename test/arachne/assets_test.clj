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
            [arachne.assets.pipeline :as pipeline]
            [arachne.core.dsl :as a]
            [arachne.core.runtime :as rt]
            [arachne.assets.dsl :as aa]
            [arachne.assets.pipeline :as p])
  (:import [java.io FileNotFoundException]))

(defn input-output-cfg
  [output-path]

  (a/runtime :test/rt [:test/output])

  (aa/input-dir :test/input "test/test-assets")
  (aa/output-dir :test/output output-path)

  (aa/pipeline [:test/input :test/output]))

(defn input-output-cfg-resource
  [output-path]

  (a/runtime :test/rt [:test/output])

  (aa/input-dir :test/input "test-assets" :classpath? true)
  (aa/output-dir :test/output output-path)

  (aa/pipeline [:test/input :test/output]))

(defn input-output-cfg-eids
  [output-path]

  (def input (aa/input-dir "test/test-assets"))
  (def output (aa/output-dir output-path))

  (a/runtime :test/rt [output])

  (aa/pipeline [input output]))

(deftest input-output-test
  (let [output-dir (.getPath (tmpdir/tmpdir!))
        scripts [`(arachne.assets-test/input-output-cfg ~output-dir)
                 `(arachne.assets-test/input-output-cfg-resource ~output-dir)
                 `(arachne.assets-test/input-output-cfg-eids ~output-dir)]]
    (doseq [script scripts]
      (let [cfg (core/build-config [:org.arachne-framework/arachne-assets] script)
            rt (core/runtime cfg :test/rt)
            rt (c/start rt)]
        (is (= #{"file1.md" "file2.md" "file3.md"}
              (->> (file-seq (io/file output-dir))
                (filter #(.isFile %))
                (map #(.getName %))
                (set))))
        (c/stop rt)))))

(defn watcher-cfg
  [input-path output-path]

  (a/runtime :test/rt [:test/output])

  (aa/input-dir :test/input input-path :watch? true)
  (aa/output-dir :test/output output-path)

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

  (aa/input-dir :test/input "test/test-assets")
  (aa/output-dir :test/output-a output-path-a)
  (aa/output-dir :test/output-b output-path-b)

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

  (aa/input-dir :test/input "test/test-assets")
  (aa/transducer :test/xform `transducer-ctor)
  (aa/output-dir :test/output output-path)

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

  (aa/input-dir :test/input-a input-a-path)
  (aa/input-dir :test/input-b input-b-path)
  (aa/output-dir :test/output output-path)

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

(defn fsview-cfg
  [input-dir]

  (a/runtime :test/rt [:test/fsview])

  (aa/input-dir :test/input input-dir)

  (a/transact [{:arachne/id :test/fsview
                :arachne.component/constructor :arachne.assets.pipeline/fileset-view}])

  (aa/pipeline [:test/input :test/fsview]))

(defn- prep-input-dir []
  (let [dir (fs/tmpdir!)]
    (.mkdir (io/file dir "a"))
    (spit (io/file dir "index.html") "<div>index</div>")
    (spit (io/file dir "a/test.html") "<div>a</div>")
    (.getPath dir)))

(deftest fsview-test
  (let [input-path (prep-input-dir)
        cfg (core/build-config [:org.arachne-framework/arachne-assets]
              `(fsview-cfg ~input-path))
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)
        fsview (rt/lookup rt [:arachne/id :test/fsview])]

    (testing "basic file"
      (is (= "<div>index</div>" (slurp (p/find-file fsview "index.html"))))
      (is (= "<div>a</div>" (slurp (p/find-file fsview "a/test.html")))))

    (testing "ring responses"
      (let [r (assets/ring-response fsview {:uri "a/test.html" :headers {}} "/" true)]
        (is (= "<div>a</div>" (slurp (:body r))))
        (is (= "text/html" (get-in r [:headers "Content-Type"]))))

      (let [r (assets/ring-response fsview {:uri "/foo/bar/baz/a/test.html" :headers {}} "/foo/bar/baz" true)]
        (is (= "<div>a</div>" (slurp (:body r))))
        (is (= "text/html" (get-in r [:headers "Content-Type"]))))

      (let [r (assets/ring-response fsview {:uri "/foo/bar/baz" :headers {}} "/foo/bar/baz" true)]
        (is (= "<div>index</div>" (slurp (:body r))))
        (is (= "text/html" (get-in r [:headers "Content-Type"])))))))