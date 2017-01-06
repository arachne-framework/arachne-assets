(set-env! :repositories
  #(conj % ["arachne-dev" {:url "http://maven.arachne-framework.org/artifactory/arachne-dev"}]))

(set-env!
  :dependencies
  `[[org.arachne-framework/arachne-buildtools "0.2.7-master-0036-e1a50db" :scope "test"]])

(require '[arachne.buildtools :refer :all])

(read-project-edn!)