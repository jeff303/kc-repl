(ns us.jeffevans.kc-repl.type-handler-load
  "Utility functions for loading type handlers from the classpath on startup"
  (:require [clojure.java.classpath :as cp]
            [clojure.tools.logging :as log])
  (:gen-class)
  (:import (java.io File FilenameFilter)
           (java.util.jar JarFile)))

(def ^:const marker-file-nm "kc-repl-handler-ns.txt")
(def filename-filter (reify FilenameFilter
                       (accept [_ _ name]
                         (.equals name marker-file-nm))))
(defn load-type-handler-from-marker-file [marker-file]
  (let [type-handler-ns (slurp marker-file)]
    (log/infof "loading handler from namespace %s" type-handler-ns)
    (require (symbol type-handler-ns))))

(defn load-type-handlers []
  (log/debugf "trying to load type handlers from jars")
  (doseq [^JarFile jar-file (cp/classpath-jarfiles)]
    (log/debugf "checking jar: %s" (.getName jar-file))
    (doseq [entry (-> (.entries jar-file)
                      enumeration-seq)]
      (when (= marker-file-nm (.getName entry))
        (with-open [input-stream (.getInputStream jar-file entry)]
          (log/debugf "found %s entry in jar file %s; attempting to load"
                      marker-file-nm
                      (.getName jar-file))
          (load-type-handler-from-marker-file input-stream)))))
  (doseq [^File cp-dir (cp/classpath-directories)]
    (when-let [marker-file (-> (.listFiles cp-dir filename-filter)
                               first)]
      (log/debugf "found %s entry in classpath dir %s; attempting to load"
                  marker-file-nm
                  (.getPath cp-dir))
      (load-type-handler-from-marker-file marker-file))))
