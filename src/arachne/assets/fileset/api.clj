(ns arachne.assets.fileset.api
  (:refer-clojure :exclude [remove filter])
  (:require
    [arachne.assets.fileset.impl :as impl]))

(defn fileset
  "Create a new, empty fileset. `blob-tmpdir` and `scratch-tmpdir` should be
  temporary directories. `cache-dir` may be either a temporary or a persistent
  directory."
  [blob-tmpdir scratch-tmpdir cache-dir]
  (impl/->TmpFileSet {} blob-tmpdir scratch-tmpdir cache-dir))

(defn commit!
  "Persist the immutable fileset to a concrete directory."
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
    (update-in added [:tree] merge (:tree changed))))

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
