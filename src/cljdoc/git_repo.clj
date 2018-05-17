(ns cljdoc.git-repo
  (:require [cljdoc.util]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as string])
  (:import  (org.eclipse.jgit.lib RepositoryBuilder
                                  Repository
                                  ObjectIdRef$PeeledNonTag
                                  ObjectIdRef$PeeledTag
                                  ObjectIdRef$Unpeeled
                                  Constants)
            (org.eclipse.jgit.revwalk RevWalk)
            (org.eclipse.jgit.treewalk TreeWalk)
            (org.eclipse.jgit.treewalk.filter PathFilter)
            (org.eclipse.jgit.api Git TransportConfigCallback)
            (org.eclipse.jgit.transport SshTransport JschConfigSessionFactory)
            (com.jcraft.jsch JSch)
            (com.jcraft.jsch.agentproxy Connector ConnectorFactory RemoteIdentityRepository)))

(def ^:private ^TransportConfigCallback ssh-callback
  (delay
    (let [factory (doto (ConnectorFactory/getDefault) (.setPreferredUSocketFactories "jna,nc"))
          connector (.createConnector factory)]
      (JSch/setConfig "PreferredAuthentications" "publickey")
      (reify TransportConfigCallback
        (configure [_ transport]
          (.setSshSessionFactory ^SshTransport transport
            (proxy [JschConfigSessionFactory] []
              (configure [host session])
              (getJSch [hc fs]
                (doto (proxy-super getJSch hc fs)
                  (.setIdentityRepository (RemoteIdentityRepository. connector)))))))))))

(defn clone [uri target-dir]
  (printf "Cloning repo %s\n" uri)
  (.. Git
      cloneRepository
      (setURI uri)
      (setDirectory target-dir)
      ;; This breaks if the URI uses http
      ;; (setTransportConfigCallback @ssh-callback)
      call))

(defn read-origin
  [^Git git-repo]
  {:post [(.startsWith % "https://")]}
  (let [remotes (->> (.. git-repo remoteList call)
                     (map (fn [remote]
                            [(keyword (.getName remote))
                             (.toString (first (.getURIs remote)))]))
                     (into {}))]
    ;; TODO for some reason on Circle CI the ssh:// protocol is used
    ;; despite us explicitly providing the http URI. Probably we should
    ;; extend this to return a map like:
    ;; {:github "some/thing"}
    ;; This would also make things more extensible for stuff like bitbucket
    ;; although admittedly I don't care about that much at this point
    (-> (:origin remotes)
        (string/replace #"^git@github.com:" "https://github.com/")
        (string/replace #"^ssh://git@" "https://"))))

(defn ->repo [^java.io.File d]
  {:pre [(some? d) (.isDirectory d)]}
  (-> (RepositoryBuilder.)
      (.setGitDir (io/file d ".git"))
      (.readEnvironment)
      (.findGitDir)
      (.build)
      (Git.)))

(defn git-checkout-repo [^Git repo rev]
  (log/infof "Checking out revision %s\n" rev)
  (.. repo checkout (setName rev) call))

(defn find-tag [^Git repo tag-str]
  (->> (.. repo tagList call)
       (filter (fn [t]
                 (= (.getName t)
                    (str "refs/tags/" tag-str))))
       first))

(defn version-tag [^Git g version-str]
  (or (find-tag g version-str)
      (find-tag g (str "v" version-str))))

(defn git-tag-names [repo]
  (->> repo
       (.tagList)
       (.call)
       (map #(->> % .getName (re-matches #"refs/tags/(.*)") second))))

(defn read-repo-meta [^Git repo version-str]
  (let [tag-obj (version-tag repo version-str)
        origin  (read-origin repo)]
    #_(assert tag-obj (format "No tag found for version-str: %s" version-str))
    (when-not tag-obj
      (log/warnf "Could not find Git tag for version %s in Git repo %s" version-str origin))
    (merge {:url     (read-origin repo)}
           (when tag-obj
             {:commit  (condp instance? tag-obj
                         ;; Not sure I really understand the difference between these two
                         ;; PeeledTags seem to have their own sha while PeeledNonTags dont
                         ObjectIdRef$PeeledTag    (.. tag-obj getPeeledObjectId getName)
                         ObjectIdRef$PeeledNonTag (.. tag-obj getObjectId getName))
              :tag     {:name (-> (.. tag-obj getName)
                                  (string/replace #"^refs/tags/" ""))
                        :sha  (.. tag-obj getObjectId getName)}}))))

(defn slurp-file-at
  "Read a file `f` in the Git repository `g` at revision `rev`.

  If the file cannot be found, return `nil`."
  [^Git g rev f]
  (let [repo        (.getRepository g)
        last-commit (.resolve repo rev)
        rev-walk    (RevWalk. repo)
        commit      (.parseCommit rev-walk last-commit)
        tree        (.getTree commit)
        tree-walk   (TreeWalk. repo)]
    (.addTree tree-walk tree)
    (.setRecursive tree-walk true)
    (.setFilter tree-walk (PathFilter/create f))
    (when (.next tree-walk)
      (slurp (.openStream (.open repo (.getObjectId tree-walk 0)))))))

(defn read-cljdoc-config [repo rev]
  (let [f "doc/cljdoc.edn"]
    (or (when rev (cljdoc.git-repo/slurp-file-at repo (.getName rev) f))
        (cljdoc.git-repo/slurp-file-at repo "master" f))))

(defn patch-level-info
  ;; Non API documentation should be updated with new Git revisions,
  ;; not only once tagged releases are published to Clojars
  ;; Some versioning scheme is required for this Non-API documentation
  ;; After recent discussion with @arrdem I'm thinking the following
  ;; version identifier might be best:
  ;;
  ;;     {:version "2.0.0" :patch-level 2}
  ;;
  ;; Where this means two changes have been made since the version
  ;; 2.0.0 has been tagged. Whether the patch-level is increased
  ;; with every commit or only with commits that modified the resulting
  ;; doc-bundle is to be decided.
  ;; TODO probably this should be captured in an ADR
  [^Git repo]

  ;; Seems like we need to walk the git commits here to retrieve tags in order
  ;; https://stackoverflow.com/questions/31836087/list-all-tags-in-current-branch-with-jgit
  )

(comment
  (def r (->repo (io/file "target/git-repo")))
  (read-repo-meta r "0.1.6")

  (def r (->repo (io/file "/Users/martin/code/02-oss/yada")))
  (read-repo-meta r "1.2.10")
  (version-tag r "1.2.10")
  (git-checkout-repo r (.getName (version-tag r "1.2.10")))
  (slurp-file-at r "master" "doc/cljdoc.edn")

  (.getPeeledObjectId -t)

  (clojure.pprint/pprint
   (read-repo-meta r "2.1.3"))

  (read-origin r)
  (find-tag r "0.1.7-alpha5")


  (.getName (find-tag r "1.2.0"))

  (read-file-at r (.getName (find-tag r "1.2.0")))
  )
