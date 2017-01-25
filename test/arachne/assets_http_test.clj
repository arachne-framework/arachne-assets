(ns arachne.assets-http-test
  (:require [clojure.test :refer :all]
            [arachne.core :as core]
            [arachne.core.config :as cfg]
            [arachne.core.runtime :as rt]
            [arachne.assets :as assets]
            [arachne.fileset :as fs]
            [arachne.fileset.tmpdir :as tmpdir]
            [com.stuartsierra.component :as c]
            [clojure.string :as str]
            [clojure.java.io :as io]

            [arachne.core.dsl :as a]
            [arachne.assets.dsl :as aa]
            [arachne.http.dsl :as h]
            [arachne.http.dsl.test :as th]

            [arachne.http :as http])
  (:import [java.io FileNotFoundException]))

(defn asset-handler-cfg
  [input-dir]

  (a/runtime :test/rt [:test/server])

  (aa/input-dir :test/input input-dir)

  (th/dummy-server :test/server 8080
    (h/endpoint :get "/" (aa/handler :test/absolute-handler))
    (h/endpoint :get "/foo/bar" (aa/handler :test/relative-handler)))

  (aa/pipeline [:test/input :test/absolute-handler]
               [:test/input :test/relative-handler]))

(defn- prep-input-dir []
  (let [dir (fs/tmpdir!)]
    (.mkdir (io/file dir "dir"))
    (spit (io/file dir "index.html") "<div>index</div>")
    (spit (io/file dir "dir/test.html") "<div>test</div>")
    (.getPath dir)))

(deftest asset-handler-test
  (let [input-path (prep-input-dir)
        cfg (core/build-config [:org.arachne-framework/arachne-assets]
              `(asset-handler-cfg ~input-path))
        rt (core/runtime cfg :test/rt)
        rt (c/start rt)
        absolute-handler (rt/lookup rt [:arachne/id :test/absolute-handler])
        relative-handler (rt/lookup rt [:arachne/id :test/relative-handler])
        ]

    (testing "basic request"
      (let [resp (http/handle absolute-handler {:uri "/dir/test.html"
                                                :headers {}})]
        (is (= "text/html" (get-in resp [:headers "Content-Type"])))
        (is (= "<div>test</div>" (slurp (:body resp))))))

    (testing "not found"
      (is (nil? (http/handle absolute-handler {:uri "/no-such-file.txt" :headers {}}))))

    (testing "request to index file"
      (let [resp (http/handle absolute-handler {:uri "/" :headers {}})]
        (is (= "<div>index</div>" (slurp (:body resp))))))

    (testing "relative handlers"
      (let [resp (http/handle relative-handler {:uri "/foo/bar/dir/test.html" :headers {}})]
        (is (= "<div>test</div>" (slurp (:body resp)))))

      (let [resp (http/handle relative-handler {:uri "/foo/bar" :headers {}})]
        (is (= "<div>index</div>" (slurp (:body resp))))))

    (c/stop rt)))

