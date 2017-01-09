(ns arachne.assets.specs
  (:require [clojure.spec :as s]
            [arachne.assets.pipeline :as p]))

(s/def ::PipelineElement (partial satisfies? p/PipelineElement))

(s/def ::Transformer #(satisfies? p/Transformer %))