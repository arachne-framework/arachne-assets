(ns arachne.assets.config
  (:require [arachne.core.config :as cfg]))

(def pipeline-rules
  "Datalog rule to recursively associate parent and child route segments in a
  routing tree."
  '[[(pipe-link ?upstream ?downstream)
     [?downstream :arachne.assets.pipeline-element/inputs ?upstream]]
    [(pipe-link ?upstream ?downstream)
     [?downstream :arachne.assets.pipeline-element/inputs ?intermediate]
     (pipe-link ?upstream ?intermediate)]

    [(cache-dir ?element ?dir)
     [?element :arachne.assets.input/cache-dir ?dir]]
    [(cache-dir ?element ?dir)
     (pipe-link ?upstream ?element)
     [?upstream :arachne.assets.input/cache-dir ?dir]]])

(defn find-cache-path
  "Given one of the elements in a pipeline, find the path to a cache directory
   (if any) associated with any one of the elements in a pipeline. Returns at
   most one cache directory."
  [cfg eid]
  (cfg/q cfg '[:find ?dir .
               :in $ % ?element
               :where
               (cache-dir ?element ?dir)]
    pipeline-rules eid))

(defn configure-inputs
  "Configure input pipeline components correctly"
  [cfg]
  (let [cs (cfg/q cfg '[:find [?c ...] :where [?c :arachne.assets.input/path _]])
        txdata (map (fn [c]
                      {:db/id c
                       :arachne.component/constructor
                       :arachne.assets.pipeline/input}) cs)]
    (if (seq txdata)
      (cfg/with-provenance :module `configure-outputs
        (cfg/update cfg txdata))
      cfg)))

(defn configure-outputs
  "Configure output pipeline components correctly"
  [cfg]
  (let [cs (cfg/q cfg '[:find [?c ...] :where [?c :arachne.assets.output/path _]])
        txdata
        (map (fn [c]
               (let [input (:db/id (first (cfg/attr cfg c :arachne.assets.pipeline-element/inputs)))]
                 {:db/id c,
                  :arachne.component/dependencies
                  {:arachne.component.dependency/entity input
                   :arachne.component.dependency/key :input},
                  :arachne.component/constructor
                  :arachne.assets.pipeline/output})) cs)]
    (if (seq txdata)
      (cfg/with-provenance :module `configure-outputs
        (cfg/update cfg txdata))
      cfg)))

(defn configure-transforms
  "Configure transform pipeline components correctly"
  [cfg]
  (let [cs (cfg/q cfg '[:find [?c ...]
                        :where [?c :arachne.assets.transform/transformer _]])
        txdata
        (map (fn [c]
               (let [input (:db/id (first (cfg/attr cfg c :arachne.assets.pipeline-element/inputs)))
                     transformer (cfg/attr cfg c :arachne.assets.transform/transformer :db/id)]
                 {:db/id c,
                  :arachne.component/dependencies
                  [{:arachne.component.dependency/entity input
                    :arachne.component.dependency/key :input}
                   {:arachne.component.dependency/entity transformer
                    :arachne.component.dependency/key :transformer}],
                  :arachne.component/constructor
                  :arachne.assets.pipeline/transform})) cs)]
    (if (seq txdata)
      (cfg/with-provenance :module `configure-outputs
        (cfg/update cfg txdata))
      cfg)))

(defn configure-merges
  "Configure merge pipeline components correctly"
  [cfg]
  (let [cs (cfg/q cfg '[:find [?c ...]
                        :where
                        [?class :db/ident :arachne.assets/Merge]
                        [?c :arachne/instance-of ?class]])
        txdata
        (map (fn [c]
               {:db/id c
                :arachne.component/dependencies
                (->> (cfg/attr cfg c :arachne.assets.pipeline-element/inputs)
                  (map :db/id)
                  (map (fn [eid] {:arachne.component.dependency/entity eid}))),
                :arachne.component/constructor :arachne.assets.pipeline/merge})
          cs)]
    (if (seq txdata)
      (cfg/with-provenance :module `configure-outputs
        (cfg/update cfg txdata))
      cfg)))
