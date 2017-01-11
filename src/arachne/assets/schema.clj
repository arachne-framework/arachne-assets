(ns arachne.assets.schema
  (:require [arachne.core.config :refer [tempid]]
            [arachne.core.config.model :as m]))

(def schema
  (concat

    (m/type :arachne.assets/Producer [:arachne.core/Component]
      "A pipeline component that can be observed to produce FileSets"
      :arachne.assets.specs/Producer)

    (m/type :arachne.assets/Consumer [:arachne.core/Component]
      "A pipeline component that observes a Producer and does something with the FileSets that it
       produces"
      (m/attr :arachne.assets.consumer/inputs :one-or-more :arachne.assets/Producer
        "The input Producers that this Consumer uses"))

    (m/type :arachne.assets/Input [:arachne.assets/Producer]
      "A Producer deriving its filesets from a location in the File System"
      (m/attr :arachne.assets.input/watch? :one-or-none :boolean
        "If true, the input will continuously monitor the specified directory for changes.")
      (m/attr :arachne.assets.input/path :one :string
        "The process-relative path of a directory containing files that will constitute the input FileSet.")
      (m/attr :arachne.assets.input/classpath? :one-or-none :boolean
        "If true, will treat the path as classpath-relative instead of process-relative.")
      (m/attr :arachne.assets.input/include :many :string
        "If present, includes only files that match this regex.")
      (m/attr :arachne.assets.input/exclude :many :string
        "If present, do not include files that match this regex."))

    (m/type :arachne.assets/Output [:arachne.assets/Consumer :arachne.assets/Producer]
      "A COnsumer that persists a fileset to a location on the File System"
      (m/attr :arachne.assets.output/path :one :string
        "The process-relative path to which to persist the files."))

    (m/type :arachne.assets/Merge [:arachne.assets/Consumer :arachne.assets/Producer]
      "A logical merge of multiple Producers into one.")

    (m/type :arachne.assets/Transform [:arachne.assets/Consumer :arachne.assets/Producer]
      "A logical transformation on a FileSet."
      (m/attr :arachne.assets.transform/transformer :one :arachne.assets/Transformer
        "The component which actually performs the transformation"))

    (m/type :arachne.assets/Transformer [:arachne/Component]
      "A component that can perform a transform operation as part of an asset pipeline."
      :arachne.assets.specs/Transformer)

     )
  )
