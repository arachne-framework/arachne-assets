(ns arachne.assets.fileset-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [arachne.assets.fileset.api :as fs])
  (:import [java.nio.file Files]
             [java.nio.file.attribute FileAttribute]))

(defn tmpdir
  "Return a new, unique tempfile as a java.io.File"
  []
  (.toFile (Files/createTempDirectory "arachne"
             (make-array FileAttribute 0))))

(defn new-fileset
  []
  (fs/fileset (tmpdir) (tmpdir) (tmpdir)))

(deftest test-basic-add-update-commit
  (let [fs (new-fileset)
        fs (fs/add fs (io/file "test/test-assets"))
        working (tmpdir)
        fs (fs/commit! fs working)]
    (spit (io/file working "file1.md") "NEW CONTENT")
    (spit (io/file working "dir1/file4.md") "NEW FILE")
    (let [fs (fs/add fs working)
          dest (tmpdir)
          fs (fs/commit! fs dest)
          files (->> (file-seq dest)
                  (filter #(.isFile %)))]
      (is (= "NEW CONTENT" (slurp (io/file dest "file1.md"))))
      (is (= #{"file1.md" "file2.md" "file3.md" "file4.md"}
             (set (map #(.getName %) files)))))))

(deftest test-remove-test
  (let [fs (new-fileset)
        fs (fs/add fs (io/file "test/test-assets"))
        fs (fs/remove fs "dir1/file3.md")
        dest (tmpdir)
        fs (fs/commit! fs dest)
        files (->> (file-seq dest)
                (filter #(.isFile %)))]
    (is (= #{"file1.md" "file2.md"}
          (set (map #(.getName %) files))))))

(deftest test-diffs
  (let [fs (new-fileset)
        fs (fs/add fs (io/file "test/test-assets"))
        working (tmpdir)
        fs (fs/commit! fs working)]
    (spit (io/file working "file1.md") "NEW CONTENT")
    (spit (io/file working "dir1/file4.md") "NEW FILE")
    (.delete (io/file working "file2.md"))
    (let [fs2 (fs/add fs working)
          fs2 (fs/remove fs2 "dir1/file3.md")]
      (is (= #{"file1.md" "dir1/file4.md"}
            (set (map :path (fs/ls (fs/diff fs fs2))))))
      (is (= #{"dir1/file4.md"}
            (set (map :path (fs/ls (fs/added fs fs2))))))
      (is (= #{"dir1/file3.md"}
            (set (map :path (fs/ls (fs/removed fs fs2))))))
      (is (= #{"file1.md"}
            (set (map :path (fs/ls (fs/changed fs fs2)))))))))

(deftest test-filtering-and-meta
  (let [fs (new-fileset)
        fs (fs/add fs (io/file "test/test-assets") :meta {:input true})
        working (tmpdir)
        fs (fs/commit! fs working)]
    (.mkdirs (io/file working "out"))
    (spit (io/file working "out/file1.out") "OUTPUT1")
    (spit (io/file working "out/file2.out") "OUTPUT2")
    (let [fs (fs/add fs working :include [#"\.out$"] :meta {:output true})
          dest (tmpdir)
          out-fs (fs/filter-by-meta fs :output)
          out-fs (fs/commit! out-fs dest)
          files (->> (file-seq dest)
                  (filter #(.isFile %)))]
      (is (= #{"file1.out" "file2.out"}
            (set (map #(.getName %) files)))))))

(deftest test-caching
  (let [fs-a (new-fileset)
        fs-b (fs/fileset (tmpdir) (tmpdir) (:cache fs-a))
        invocations (atom 0)
        cachefn (fn [dir]
                  (swap! invocations inc)
                  (spit (io/file dir "file.out") "OUTPUT"))
        fs-a (fs/add-cached fs-a "aaa" cachefn)
        fs-b (fs/add-cached fs-b "aaa" cachefn)
        dest-a (tmpdir)
        dest-b (tmpdir)]
    (fs/commit! fs-a dest-a)
    (fs/commit! fs-b dest-b)
    (is (= "OUTPUT" (slurp (io/file dest-a "file.out"))))
    (is (= "OUTPUT" (slurp (io/file dest-b "file.out"))))
    (is (= 1 @invocations))))