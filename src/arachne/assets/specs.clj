(ns arachne.assets.specs
  (:require [clojure.spec :as s]
            [arachne.assets.pipeline :as p]))

(s/def ::PipelineElement (partial satisfies? p/PipelineElement))

(defn valid-transformer?
  "Validate that the transformer of the given pipeline element is of the correct type"
  [pipeline-element]
  (satisfies? p/Transformer (:transformer pipeline-element)))

(s/def ::Transform valid-transformer?)