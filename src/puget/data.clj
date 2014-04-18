(ns puget.data
  "Code to handle structured data, usually represented as EDN."
  (:refer-clojure :exclude [read read-string])
  (:require
    [clojure.data.codec.base64 :as b64]
    [clojure.edn :as edn])
  (:import
    (java.net URI)
    (java.text SimpleDateFormat)
    (java.util Date TimeZone UUID)))


;; TOTAL-ORDERING COMPARATOR

(defn- type-priority
  "Determines the 'priority' of the given value based on its type."
  [x]
  (let [predicates [nil? false? true? number? char? string?
                    keyword? symbol? list? vector? set? map?]
        priority (->> predicates
                      (map vector (range))
                      (some (fn [[i p]] (when (p x) i))))]
    (or priority (count predicates))))


(defn total-order
  "Comparator function that provides a total-ordering of EDN values.

  Values of different types sort in order of their types:
  - nil
  - Boolean
  - Number
  - Character
  - String
  - Keyword
  - Symbol
  - List
  - Vector
  - Set
  - Map

  All other types are sorted by print representation."
  [a b]
  (if (= a b) 0
    (let [pri-a (type-priority a)
          pri-b (type-priority b)]
      (cond (< pri-a pri-b) -1
            (> pri-a pri-b)  1

            (instance? java.lang.Comparable a)
            (compare a b)

            :else
            (compare (pr-str a) (pr-str b))))))



;; TAGGED VALUE PROTOCOL

(defprotocol TaggedValue
  (edn-tag [this] "Return the EDN tag symbol for this data type.")
  (edn-value [this] "Return the EDN value to follow the tag."))


(defn edn-str
  "Converts the given TaggedValue data to a tagged EDN string."
  ^String
  [v]
  (str \# (edn-tag v) \space (pr-str (edn-value v))))



;; DATA READERS

(def data-readers
  "Atom containing a map of data readers supported by Puget."
  (atom {} :validator map?))


(defn register-reader!
  "Registers a function as the data reader for an EDN tag."
  [tag f]
  {:pre [(symbol? tag)]}
  (swap! data-readers assoc tag f))


(defmacro defreader
  [tag & body]
  (let [reader-fn (symbol (str "read-" (name tag)))]
    `(do
       (defn ~reader-fn ~@body)
       (register-reader! (quote ~tag) ~reader-fn))))


(defn read
  "Wraps clojure.edn/read to provide the currently-defined data readers as
  default readers."
  ([] (read *in*))
  ([stream] (read nil stream))
  ([opts stream]
   (let [readers (merge @data-readers (:readers opts))
         opts (assoc opts :readers readers)]
     (edn/read opts stream))))


(defn read-string
  "Wraps clojure.edn/read-string to provide the currently-defined data readers
  as default readers."
  ([string] (read-string nil string))
  ([opts string]
   (let [readers (merge @data-readers (:readers opts))
         opts (assoc opts :readers readers)]
     (edn/read-string opts string))))



;; EXTENSION FUNCTIONS

(defmacro defprint-method
  "Defines a print-method for the given class which writes out the EDN
  serialization from `edn-str`."
  [t]
  `(defmethod print-method ~t
     [v# ^java.io.Writer w#]
     (.write w# (edn-str v#))))


(defmacro extend-tagged-value
  "Extends the TaggedValue protocol with implementations which return the
  given symbol as the tag and use the body to calculate the value. The symbol
  'this' is bound to the data. This macro also defines a print-method which
  delegates to edn-str."
  [t tag expr]
  `(do
     (extend-type ~t
       TaggedValue
       (edn-tag [~'this] (quote ~tag))
       (edn-value [~'this]
         ~(if (or (symbol? expr) (keyword? expr))
            (list expr 'this)
            expr)))
     (defprint-method ~t)))


(defmacro extend-tagged-str
  [c tag]
  `(extend-tagged-value ~c ~tag str))


(defmacro extend-tagged-map
  [c tag]
  `(extend-tagged-value ~c ~tag (into {} (seq ~'this))))



;; BUILT-IN EDN TAGS

; #inst - Date-time instant as an ISO-8601 string.
(defn- format-utc
  "Produces an ISO-8601 formatted date-time string from the given Date."
  [^Date date]
  (let [date-format (doto (java.text.SimpleDateFormat.
                            "yyyy-MM-dd'T'HH:mm:ss.SSS-00:00")
                       (.setTimeZone (TimeZone/getTimeZone "GMT")))]
    (.format date-format date)))


(extend-tagged-value Date inst format-utc)


; #uuid - Universally-unique identifier string.
(extend-tagged-str UUID uuid)


; #bin - Binary data in the form of byte arrays.
(extend-tagged-value
  (class (byte-array 0)) bin
  (->> this b64/encode (map char) (apply str)))


(defreader bin
  "Reads a base64-encoded string into a byte array."
  ^bytes
  [^String bin]
  (b64/decode (.getBytes bin)))


; #uri - Universal Resource Identifier string.
(extend-tagged-str URI uri)


(defreader uri
  "Constructs a URI from a string value."
  ^URI
  [^String uri]
  (URI. uri))


; #??? - default handling function
(defrecord GenericTaggedValue
  [tag value]

  TaggedValue
  (edn-tag [this] tag)
  (edn-value [this] value))


(defprint-method GenericTaggedValue)


(defn tagged-value
  "Creates a generic tagged value record to represent some EDN value. This is
  suitable for use as a default-data-reader function."
  [tag value]
  {:pre [(symbol? tag)]}
  (->GenericTaggedValue tag value))
