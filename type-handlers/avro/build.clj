(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'net.clojars.jeff_evans/kc-repl-type-handler-avro)
(def version (format "1.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def src-jar-file (format "target/%s-%s-avro-handler-src.jar" (name lib) version))
(def uberjar-file (format "target/%s-%s-avro-handler.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
      (b/delete {:path "target"}))

(defn jar [_]
      (b/write-pom {:class-dir class-dir
                    :lib lib
                    :version version
                    :basis @basis
                    :src-dirs ["src"]})
      (b/copy-dir {:src-dirs ["src" "resources"]
                   :target-dir class-dir})
      (b/jar {:class-dir class-dir
              :jar-file  src-jar-file}))

(defn uberjar [_]
      (clean nil)
      (b/copy-dir {:src-dirs ["src" "resources"]
                   :target-dir class-dir})
      (b/compile-clj {:basis @basis
                      :class-dir class-dir})
      (b/uber {:class-dir class-dir
               :uber-file uberjar-file
               :basis @basis}))

(defn run-tests [_]
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :class-dir class-dir})
  (let [{:keys [exit] :as res} (b/process {:command-args ["clj" "-M:test:test-dependencies"]})]
    (when-not (zero? exit)
      (throw (ex-info (str "run-tests failed") res)))))
