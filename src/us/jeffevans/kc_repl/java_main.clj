(ns us.jeffevans.kc-repl.java-main
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [us.jeffevans.kc-repl :as kcr]
            [us.jeffevans.kc-repl.type-handler-load :as thl]
            [instaparse.core :as insta])
  (:gen-class))

(defn- get-input-line []
  (read-line))

(def java-cmd-args
  (insta/parser
    "<S> = CMD_NAME <WHITESPACE?> (SHORT_ARG | LONG_ARG)*
     CMD_NAME = #'[a-zA-Z][\\-_\\+\\!a-zA-Z0-9]*'
     BAREWORD = #'[a-zA-Z0-9][\\-_\\+\\.\\$a-zA-Z0-9]*'
     ARG_PARAM = (SINGLE_QUOTED_WORD | DOUBLE_QUOTED_WORD | BAREWORD)
     LONG_ARG_WORD = <'-'> <'-'> BAREWORD
     LONG_ARG = LONG_ARG_WORD <WHITESPACE?> (ARG_PARAM <WHITESPACE?>)*
     SHORT_ARG_WORD = <'-'> BAREWORD
     SHORT_ARG = SHORT_ARG_WORD <WHITESPACE?> (ARG_PARAM <WHITESPACE?>)*
     WHITESPACE = #'\\s+'
     SINGLE_QUOTED_WORD = <'\\''> (ANY_WORD <WHITESPACE?>)* <'\\''>
     DOUBLE_QUOTED_WORD = <'\\\"'> (ANY_WORD <WHITESPACE?>)* <'\\\"'>
     <ANY_WORD> = #'[\\-_\\.a-zA-Z0-9]+'"))

(defn- quoted-word->vals [quoted-word]
  (str/join " " quoted-word))

(defn- arg->vals [[arg-kind [_ arg-nm]] & arg-prms]
  (let [arg-nm-str (case arg-kind
                     :CMD_NAME arg-nm
                     :LONG_ARG_WORD (format "--%s" arg-nm)
                     :SHORT_ARG_WORD (format "-%s" arg-nm))]
    (if (nil? arg-prms)
      {arg-nm-str []}
      (reduce (fn [acc [_ [arg-type & more]]]
                (let [reduced-arg (case arg-type
                                    :BAREWORD
                                    (first more)

                                    (:DOUBLE_QUOTED_WORD :SINGLE_QUOTED_WORD)
                                    (quoted-word->vals more))]
                  (-> (update acc arg-nm-str #(or % []))
                      (update arg-nm-str conj reduced-arg))))
              {}
              arg-prms))))
(defn parse-and-transform-input-line [input-line]
  (let [arg-maps (insta/transform {:CMD_NAME (fn [cmd-nm]
                                               {::command-name cmd-nm})
                                   :SHORT_ARG arg->vals
                                   :LONG_ARG arg->vals}
                                  (java-cmd-args input-line))]
    (let [merged-args (apply merge arg-maps)
          cmd-nm      (::command-name merged-args)]
      [cmd-nm (dissoc merged-args ::command-name)])))
(defn- constant-seq [value]
  (cycle (repeat 1 value)))

(defn parse-line-as-cli-args [input-line]
  (let [[cmd-nm parsed-args] (parse-and-transform-input-line input-line)]
    (reduce-kv (fn [acc arg-nm arg-vals]
                 (if (empty? arg-vals)
                   (conj acc arg-nm)
                   (vec (concat acc (interleave (constant-seq arg-nm) arg-vals)))))
               [cmd-nm]
               parsed-args)))

(defn -main
  "Entrypoint for the uberjar (Java) main.

  config-fname is the path to the Kafka consumer config file
  final-callback is a function that will be executed after stop, with the kcr-client passed as the only arg"
  [config-fname & [final-callback & _]]
  (log/infof "-main started with %s%n" config-fname)

  (thl/load-type-handlers)
  (kcr/entrypoint config-fname
    (fn [_]
      (let [continue? (atom true)]
        (while @continue?
          (let [input       (get-input-line)
                [op & args] (if (str/blank? input) ["stop"] (parse-line-as-cli-args input))
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

            (reset! continue? (not= "stop" op))))
        (when-not (nil? final-callback)
          (final-callback kcr/*client*))))))

