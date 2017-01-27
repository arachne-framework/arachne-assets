(ns arachne.assets
  (:require [arachne.core.config :as cfg]
            [arachne.assets.schema :as schema]
            [arachne.assets.config :as config]
            [arachne.assets.pipeline :as p]
            [arachne.assets.specs]
            [ring.middleware.file-info :as file-info]
            [ring.middleware.content-type]
            [ring.util.mime-type :as mime]
            [ring.util.response :as res])
  (:import (java.net URI)))

(defn ^:no-doc schema
  "Return the schema for the assets module"
  []
  schema/schema)

(defn ^:no-doc configure
  "Configure the assets module"
  [cfg]
  (-> cfg
    (config/configure-input-dependencies)))


(defn ring-response
  "Given a FSView pipeline component, and a ring request returns a Ring Response for a file at the
   given path, relative to the provided relative-path.

   If index? paramter is true, returns /index.html files if
   the given path refers to a directory.

   The response will include MIME type and last-modified headers in standard Ring format

   Returns nil if the file does not exist."
  [fsview request rel-path index?]
  (let [path (str (.relativize (URI. rel-path) (URI. (:uri request))))]
    (if-let [f (p/find-file fsview path)]
      (-> {:stats 200 :body f}
        (file-info/file-info-response request)
        (res/content-type (or (mime/ext-mime-type path)
                            "application/octet-stream")))
      (when index?
        (let [path (str (.relativize (URI. "/")
                          (.normalize (URI. (str path "/index.html")))))]
          (ring-response fsview (assoc request :uri path) rel-path false))))))