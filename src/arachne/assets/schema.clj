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
      (m/attr :arachne.assets.consumer/inputs :one-or-more :component :arachne.assets/Input
        "The input Producers that this Consumer uses"))

    (m/type :arachne.assets/Input [:arachne/Entity]
      "A specific input to a consumer"
      (m/attr :arachne.assets.input/entity :one :ref :arachne.assets/Producer
        "The producer element.")
      (m/attr :arachne.assets.input/name :one :keyword
        "Name that identifies this input."))

    (m/type :arachne.assets/InputDirectory [:arachne.assets/Producer]
      "A Producer deriving its filesets from a location in the File System"
      (m/attr :arachne.assets.input-directory/watch? :one-or-none :boolean
        "If true, the input will continuously monitor the specified directory for changes.")
      (m/attr :arachne.assets.input-directory/path :one :string
        "The process-relative path of a directory containing files that will constitute the input FileSet.")
      (m/attr :arachne.assets.input-directory/classpath? :one-or-none :boolean
        "If true, will treat the path as classpath-relative instead of process-relative.")
      (m/attr :arachne.assets.input-directory/include :many :string
        "If present, includes only files that match this regex.")
      (m/attr :arachne.assets.input-directory/exclude :many :string
        "If present, do not include files that match this regex."))

    (m/type :arachne.assets/OutputDirectory [:arachne.assets/Consumer :arachne.assets/Producer]
      "A Consumer that persists a fileset to a location on the File System"
      (m/attr :arachne.assets.output-directory/path :one :string
        "The process-relative path to which to persist the files."))

    (m/type :arachne.assets/Transducer [:arachne.assets/Consumer :arachne.assets/Producer]
      "A producer/consumer which applies a transducer to filesets that pass through it."
      (m/attr :arachne.assets.transducer/constructor :one :keyword
        "The fully-qualified name of a function that, when passed a :arachne.assets/Transducer component, returns a Clojure transducer."))
    )
  )
