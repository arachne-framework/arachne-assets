(ns arachne.assets.specs
  (:require [clojure.spec.alpha :as s]
            [arachne.assets.pipeline :as p]))

(s/def ::Producer (partial satisfies? p/Producer))
