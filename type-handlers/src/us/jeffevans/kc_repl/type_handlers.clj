(ns us.jeffevans.kc-repl.type-handlers
  (:gen-class :name us.jeffevans.KcRepl))

(defprotocol type-handler
             (parse-bytes [this ^String topic ^bytes b])
             (->clj [this obj])
             (set-config! [this k args]))

(defmulti create-type-handler (fn [type* & _]
                                  type*))

(defmulti parse-type
          "A multimethod to handle parsing a byte array, which can be of various ConsumerRecord key or value types"
          (fn [type* & _]
              type*))


