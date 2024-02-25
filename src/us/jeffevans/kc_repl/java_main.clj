(ns us.jeffevans.kc-repl.java-main
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [us.jeffevans.kc-repl :as kcr]
            [us.jeffevans.kc-repl.type-handler-load :as thl])
  (:gen-class :name us.jeffevans.KcRepl)
  (:import (org.apache.logging.log4j.core.config Configurator)))

(def cli-options
  ;; An option with a required argument
  [["-p" "--port PORT" "Port number"
    :default 80
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ;; A non-idempotent option (:default is applied first)
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :update-fn inc] ; Prior to 0.4.1, you would have to use:
   ;; :assoc-fn (fn [m k _] (update-in m [k] inc))
   ;; A boolean option defaulting to nil
   ["-h" "--help"]])

(defn- get-input-line []
  (read-line))

(defn -main
  "Entrypoint for the uberjar (Java) main.

  TODO: actually get it working"
  [config-fname & more]
  (log/infof "-main started with %s%n" config-fname)

  (thl/load-type-handlers)
  (kcr/entrypoint config-fname
    (fn [_]
      (let [continue? (atom true)]
        (while @continue?
          (let [input       (get-input-line)
                [op & args] (if (nil? input) ["stop"] (str/split input #" "))
                {:keys [::kcr/invoke-fn ::kcr/opts-spec ::kcr/print-offsets?]} (get @#'kcr/java-cmds op)]
            (log/tracef "got input %s, op %s, args %s, opts-spec %s" input op (pr-str args) (pr-str opts-spec))
            (println)
            (if (nil? opts-spec)
              (kcr/print-error-line (format "%s is not a valid command; must be one of:%n%s"
                                            op
                                            (str/join "\n" (reduce-kv (fn [acc k v]
                                                                        (conj acc (format "%s (%s)"
                                                                                          k (::kcr/description v))))
                                                                      []
                                                                      @#'kcr/java-cmds))))
              (let [parsed (cli/parse-opts args opts-spec)]
                (log/tracef "parsed: %s" (pr-str parsed))
                (if (:errors parsed)
                  (kcr/print-error-line (format "Error invoking %s command: %s%n%s"
                                                op
                                                (pr-str (:errors parsed))
                                                (:summary parsed)))
                  (when (fn? invoke-fn)
                    (let [res (invoke-fn parsed)]
                      (when (and res (not= res ::kcr/ok))
                        (kcr/print-output-line (pr-str res)))
                      (when print-offsets?
                        (kcr/print-assignments* (kcr/current-assignments kcr/*client*))))))))

            (reset! continue? (not= "stop" op))))))))

