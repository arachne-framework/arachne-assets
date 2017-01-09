(ns arachne.assets.schema
  (:require [arachne.core.config :refer [tempid]]
            [arachne.core.config.model :as m]))

(def schema
  (concat
    (m/type :arachne.assets/PipelineElement [:arachne.core/Component]
      "A component that is a logical element of an asset pipeline, resolvable to
           an Arachne fileset"
      :arachne.assets.specs/PipelineElement
      (m/attr :arachne.assets.pipeline-element/inputs :many :arachne.assets/PipelineElement
        "The input pipeline elements."))

    (m/type :arachne.assets/Input [:arachne.assets/PipelineElement]
      "A PipelineElement deriving its filesets from a location in the File System"
      (m/attr :arachne.assets.input/watch? :one-or-none :boolean
        "If true, the input will continuously monitor the specified directory for changes.")
      (m/attr :arachne.assets.input/cache-dir :one-or-none :string
        "The process-relative path of a directory that will be used as a persistent cache. If absent, the system will use a temp directory. Note that although there can be multiple inputs linked together in a single pipeline, there should be at most one different cache dir specified.")
      (m/attr :arachne.assets.input/path :one :string
        "The process-relative path of a directory containing files that will constitute the input FileSet.")
      (m/attr :arachne.assets.input/classpath? :one-or-none :boolean
        "If true, will treat the path as classpath-relative instead of process-relative.")
      (m/attr :arachne.assets.input/include :many :string
        "If present, includes only files that match this regex.")
      (m/attr :arachne.assets.input/exclude :many :string
        "If present, do not include files that match this regex."))

    (m/type :arachne.assets/Output [:arachne.assets/PipelineElement]
      "A PipelineElement that persists a fileset to a location on the File System"
      (m/attr :arachne.assets.output/path :one :string
        "The process-relative path to which to persist the files."))

    (m/type :arachne.assets/Merge [:arachne.assets/PipelineElement]
      "A logical merge of multiple PipelineElements into one.")

    (m/type :arachne.assets/Transform [:arachne.assets/PipelineElement]
      "A logical transformation on a FileSet."
      (m/attr :arachne.assets.transform/transformer :one :arachne.assets/Transformer
        "The component which actually performs the transformation"))

    (m/type :arachne.assets/Transformer [:arachne/Component]
      "A component that can perform a transform operation as part of an asset pipeline."
      :arachne.assets.specs/Transformer)

     )
  )
