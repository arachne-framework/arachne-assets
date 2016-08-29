(ns arachne.assets.fileset.api
  (:refer-clojure :exclude [remove filter empty merge])
  (:require  [arachne.assets.fileset.impl :as impl]
             [arachne.assets.fileset.util :as futil]
             [arachne.assets.fileset.tmpdir :as tmpdir]))

;; These can be truly global, because they only contain immmutable
;; content-addressed hard links or one-off subdirectories.
(def global-blob-dir (memoize tmpdir/tmpdir!))
(def global-scratch-dir (memoize tmpdir/tmpdir!))
(def default-cache-dir (memoize tmpdir/tmpdir!))

(defn fileset
  "Create a new, empty fileset. Optionally, takes a directory to use as a
  persistent cache, otherwise uses a process-wide temporary directory."
  ([] (fileset (default-cache-dir)))
  ([cache-dir]
   (impl/->TmpFileSet {} (global-blob-dir) (global-scratch-dir) cache-dir)))

;; Idea: we could theoretically do garbage collection:
;; - find all instances of FileSet (would require registering in a weak map at creation)
;; - find all TmpFiles in all FileSets
;; - delete all blobs not referenced by a TmpFile
;; - but it's probably unnecessary

(defn commit!
  "Persist the immutable fileset to a concrete directory. Note that the emitted
  files are hard links to the fileset's internal blob storage, and therefore
  immutable. If you want to modify them, you will need to copy them first."
  [fs dir]
  (impl/-commit! fs dir))

(defn add
  "Return a Fileset with all the files in the given directory added. Options are
  as follows:

  :include - only add files that match regexes in this collection
  :exclude - do not add files that match regexes in this collection (takes
             priority over :include)
  :meta - map of metadata that will be added each file
  :mergers - a map of regex patterns to merge functions. When a file to be added
            already exists in the fileset, and its name matches a key in the
            mergers map, uses the specified merge function to determine the
            resulting contents of the file.

            The default behavior (with no merge function) is to replace the file.

            Merge functions take three arguments: an InputStream of the contents
            of the existing file, an InputStream of the contents of the new
            file, and an OutputStream that will contain the contents of the
            resulting file. The streams will be closed after the merge function
            returns (meaning that it should do all its processing eagerly.)"
  [fileset dir & {:keys [include exclude mergers meta] :as opts}]
  (impl/-add fileset dir opts))

(defn add-cached
  "Like add, but takes a cache-key (string) and cache-fn instead of a directory.
  If the cache key is not found in the Fileset's cache, then the cache-fn is
  invoked with a single argument - a directory in which to write the files that
  boot should add to the cache. In either case, the cached files are added to
  the fileset."
  [fileset cache-key cache-fn & {:keys [include exclude mergers meta] :as opts}]
  (impl/-add-cached fileset cache-key cache-fn opts))

(declare filter)
(defn remove
  "Return a Fileset with the specified files removed. Files may be identified as
  path strings or TmpFile instances."
  [fileset & file-identifiers]
  (let [file-identifiers (set file-identifiers)]
    (filter fileset #(not (or (file-identifiers %)
                              (file-identifiers (impl/-path %)))))))

(defn diff
  "Return a Fileset containing only the files that are different in `added` and
  `before` or not present in `before`"
  [before after]
  (let [{:keys [added changed]}
        (impl/diff* before after nil)]
    (update-in added [:tree] clojure.core/merge (:tree changed))))

(defn removed
  "Return a Fileset containing only the files present in `before` and not in
  `after`"
  [before after]
  (:removed (impl/diff* before after nil)))

(defn added
  "Return a Fileset containing only the files that are present in `after` but
  not `before`"
  [before after]
  (:added (impl/diff* before after nil)))

(defn changed
  "Return a Fileset containing only the files that are different in `after` and
  `before`"
  [before after]
  (:changed (impl/diff* before after nil)))

(defn filter
  "Return a fileset containing only files for which the predicate returns true
  when applied to the TempFile"
  [fileset pred]
  (assoc fileset :tree (reduce-kv (fn [xs k v]
                                    (if (pred v)
                                      (assoc xs k v)
                                      xs))
                         {} (:tree fileset))))

(defn filter-by-meta
  "Return a fileset containing only files for which the predicate returns true
  when applied to the metadata of a TempFile"
  [fileset pred]
  (filter fileset (comp pred :meta)))

(defn ls
  "Return the the files in the fileset as a set of TmpFile records"
  [fileset]
  (impl/-ls fileset))

(defn empty
  "Create a new empty fileset with the same cache dir as the input"
  [fs]
  (filter fs (constantly false)))

(defn- merge-tempfile
  "Merge two tempfiles, logging a warning if one would overwrite the other"
  [a b]
  (let [[winner loser] (if (< (impl/-time a) (impl/-time b)) [b a] [a b])]
    (when-not (and (= (impl/-hash a) (impl/-hash b))
                   (= (impl/-meta a) (impl/-meta b)))
      (futil/warn "File at path %s was overwritten while merging filesets. Using the file timestamped %s, which is newer than %s"
        (impl/-path winner) (impl/-time winner) (impl/-time loser)))
    (update winner :meta #(clojure.core/merge %1 (impl/-meta loser)))))

(defn merge
  "Merge multiple filesets. If a path exists in more than one fileset, with
  different content, the most recent one is used and a warning is logged."
  ([fs] fs)
  ([a b]
   (assoc a :tree (merge-with merge-tempfile (:tree a) (:tree b))))
  ([a b & more]
   (reduce merge a (cons b more))))