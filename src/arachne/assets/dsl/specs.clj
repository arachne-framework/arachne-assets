(ns arachne.assets.dsl.specs
  (:require [clojure.spec :as s]
            [arachne.core.dsl.specs :as cspec]))

(s/def ::path string?)

(s/def ::pattern (partial instance? java.util.regex.Pattern))

(s/def ::pattern-or-patterns
  (s/or :pattern ::pattern
    :patterns (s/coll-of ::pattern :min-count 1)))

(s/def ::include ::pattern-or-patterns)
(s/def ::exclude ::pattern-or-patterns)

(s/def ::watch? boolean?)

(s/def ::input-args
  (s/cat :arachne-id (s/? ::cspec/id)
    :path ::path
    :opts (s/keys* :opt-un [::watch? ::include ::exclude])))

(s/fdef arachne.assets.dsl/input-dir
  :args ::input-args)

(s/fdef arachne.assets.dsl/input-resource
  :args ::input-args)

(s/fdef arachne.assets.dsl/output-dir
  :args (s/cat :arachne-id (s/? ::cspec/id)
               :input ::cspec/identity
               :path ::path))

(s/fdef arachne.assets.dsl/transform
  :args (s/cat :arachne-id (s/? ::cspec/id)
               :input ::cspec/identity
               :transformer ::cspec/identity))

(s/fdef arachne.assets.dsl/merge
  :args (s/cat :arachne-id (s/? ::cspec/id)
               :inputs (s/coll-of ::cspec/identity :min-count 1)))
