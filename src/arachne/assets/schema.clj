(ns arachne.assets.schema
  (:require [arachne.core.config :refer [tempid]]
            [arachne.core.config.ontology :as o]))

(def schema
  (concat
     (o/class :arachne.assets/PipelineElement [:arachne.core/Component]
       "A component that is a logical element of an asset pipeline, resolvable to
       an Arachne fileset"
       :arachne.assets.specs/PipelineElement
       (o/attr :arachne.assets.pipeline-element/inputs :many :arachne.assets/PipelineElement
         "The process-relative path to which to persist the files."))

     (o/class :arachne.assets/Input [:arachne.assets/PipelineElement]
       "A PipelineElement deriving its filesets from a location in the File System"
       (o/attr :arachne.assets.input/watch? :one-or-none :boolean
         "If true, the input will continuously monitor the specified directory for changes.")
       (o/attr :arachne.assets.input/cache-dir :one-or-none :string
         "The process-relative path of a directory that will be used as a persistent cache. If absent, the system will use a temp directory. Note that although there can be multiple inputs linked together in a single pipeline, there should be at most one different cache dir specified.")
       (o/attr :arachne.assets.input/path :one :string
         "The process-relative path of a directory containing files that will constitute the input FileSet.")
       (o/attr :arachne.assets.input/classpath? :one-or-none :boolean
         "If true, will treat the path as classpath-relative instead of process-relative.")
       (o/attr :arachne.assets.input/include :many :string
         "If present, includes only files that match this regex.")
       (o/attr :arachne.assets.input/exclude :many :string
         "If present, do not include files that match this regex."))

     (o/class :arachne.assets/Output [:arachne.assets/PipelineElement]
       "A PipelineElement that persists a fileset to a location on the File System"
       (o/attr :arachne.assets.output/path :one :string
         "The process-relative path to which to persist the files."))

     (o/class :arachne.assets/Merge [:arachne.assets/PipelineElement]
       "A logical merge of multiple PipelineElements into one.")

     (o/class :arachne.assets/Transform [:arachne.assets/PipelineElement]
       "A logical transformation on a FileSet."
       :arachne.assets.specs/Transform
       (o/attr :arachne.assets.transform/transformer :one :arachne.core/Component
         "A component satisfying the arachne.assets.pipeline/Transformer protocol which actually performs the transformation"))

     )
  )
