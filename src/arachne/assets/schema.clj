(ns arachne.assets.schema
  (:require [arachne.core.config :refer [tempid]]
            [arachne.core.config.ontology :as o]))

(def schema

  (o/class :arachne.assets/Fileset []
    "A logical set of files to transform"
    (o/attr :arachne.assets.fileset/path :one :string
      "The file system path of this fileset"))

  )
