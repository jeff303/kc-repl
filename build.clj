(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'net.clojars.jeff_evans/kc-repl)
(def version (format "2.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def src-jar-file (format "target/%s-%s.jar" (name lib) version))
(def uberjar-file (format "target/%s-%s-standalone.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def main 'us.jeffevans.kc-repl.java-main)

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
                      :ns-compile [main]
                      :class-dir class-dir})
      (b/uber {:class-dir class-dir
               :uber-file uberjar-file
               :basis @basis
               :main main}))

(defn run-tests [_]
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile [main]
                  :class-dir class-dir})
  (let [{:keys [exit] :as res} (b/process {:command-args ["clj" "-M:test:test-dependencies:type-handlers"]})]
    (when-not (zero? exit)
      (throw (ex-info (str "run-tests failed") res)))))
