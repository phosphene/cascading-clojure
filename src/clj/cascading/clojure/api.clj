(ns cascading.clojure.api
  (:refer-clojure :exclude (count filter mapcat map))
  (:use [clojure.contrib.seq-utils :only [find-first indexed]])
  (:import (cascading.tuple Fields)
           (cascading.scheme TextLine)
           (cascading.flow Flow FlowConnector)
           (cascading.operation Identity)
           (cascading.operation.regex RegexGenerator RegexFilter)
           (cascading.operation.aggregator Count)
           (cascading.pipe Pipe Each Every GroupBy CoGroup)
           (cascading.pipe.cogroup InnerJoin)
           (cascading.scheme Scheme)
           (cascading.tap Hfs Lfs Tap)
           (java.util Properties Map UUID)
           (cascading.clojure ClojureFilter ClojureMapcat ClojureMap
                              ClojureAggregator)
           (clojure.lang Var)
           (java.lang RuntimeException)))

(defn ns-fn-name-pair [v]
  (let [m (meta v)]
    [(str (:ns m)) (str (:name m))]))

(defn fn-spec [v-or-coll]
  "v-or-coll => var or [var & params]
   Returns an Object array that is used to represent a Clojure function.
   If the argument is a var, the array represents that function.
   If the argument is a coll, the array represents the function returned
   by applying the first element, which should be a var, to the rest of the
   elements."
  (cond
    (var? v-or-coll)
      (into-array Object (ns-fn-name-pair v-or-coll))
    (coll? v-or-coll)
      (into-array Object
        (concat
          (ns-fn-name-pair (first v-or-coll))
          (next v-or-coll)))
    :else
      (throw (IllegalArgumentException. (str v-or-coll)))))

(defn- collectify [obj]
  (if (sequential? obj) obj [obj]))

(defn fields
  {:tag Fields}
  [obj]
  (if (or (nil? obj) (instance? Fields obj))
    obj
    (Fields. (into-array String (collectify obj)))))

(defn- fields-obj? [obj]
  "Returns true for a Fileds instance, a string, or an array of strings."
  (or
    (instance? Fields obj)
    (string? obj)
    (and (sequential? obj) (every? string? obj))))

(defn- idx-of-first [coll pred]
  (first (find-first #(pred (last %)) (indexed coll))))

(defn- parse-func [obj]
  "obj =>
   #'func
   [#'func]
   [overridefields #'func]
   [#'func params...]
   [overridefields #'func params...]"
  (let [obj         (collectify obj)
        i           (idx-of-first obj var?)
        spec        (fn-spec (drop i obj))
        funcvar     (nth obj i)
        func-fields (fields (if (> i 0) (first obj) ((meta funcvar) :fields)))]
    [func-fields spec]))

(defn- parse-args
  ([arr]
   (parse-args arr Fields/RESULTS))
  ([arr defaultout]
   (let [i                  (idx-of-first arr #(not (fields-obj? %)))
         infields           (if (> i 0) (fields (first arr)) Fields/ALL)
         [func-fields spec] (parse-func (nth arr i))
         outfields          (if (< i (dec (clojure.core/count arr)))
                              (fields (last arr)) defaultout)]
     [infields func-fields spec outfields])))

(defn- uuid []
  (str (UUID/randomUUID)))

(defn pipe
  "Returns a Pipe of the given name, or if one is not supplied with a
   unique random name."
  ([]
   (Pipe. (uuid)))
  ([#^String name]
   (Pipe. name)))

(defn filter [#^Pipe previous & args]
  (let [[in-fields _ spec _] (parse-args args)]
    (Each. previous in-fields
      (ClojureFilter. spec))))

(defn mapcat [#^Pipe previous & args]
  (let [[in-fields func-fields spec out-fields] (parse-args args)]
    (Each. previous in-fields
      (ClojureMapcat. func-fields spec) out-fields)))

(defn map [#^Pipe previous & args]
  (let [[in-fields func-fields spec out-fields] (parse-args args)]
    (Each. previous in-fields
      (ClojureMap. func-fields spec) out-fields)))

(defn aggregate [#^Pipe previous in-fields out-fields
                 start aggregate complete]
  (Every. previous (fields in-fields)
    (ClojureAggregator. (fields out-fields)
      (fn-spec start) (fn-spec aggregate) (fn-spec complete))))

(defn group-by [#^Pipe previous group-fields]
  (GroupBy. previous (fields group-fields)))

(defn count [#^Pipe previous #^String count-fields]
  (Every. previous
    (Count. (fields count-fields))))

(defn inner-join
  ([[#^Pipe lhs #^Pipe rhs] [lhs-fields rhs-fields]]
   (CoGroup. lhs (fields lhs-fields) rhs (fields rhs-fields)
     (InnerJoin.)))
  ([[#^Pipe lhs #^Pipe rhs] [lhs-fields rhs-fields] declared-fields]
   (CoGroup. lhs (fields lhs-fields) rhs (fields rhs-fields)
     (fields declared-fields) (InnerJoin.))))

(defn select [#^Pipe previous keep-fields]
  (Each. previous (fields keep-fields) (Identity.)))

(defn text-line
 ([]
  (TextLine. Fields/FIRST))
 ([field-names]
  (TextLine. (fields field-names) (fields field-names))))

(defn path [x]
  (if (string? x) x (.getAbsolutePath x)))

(defn hfs-tap [#^Scheme scheme path-or-file]
  (Hfs. scheme (path path-or-file)))

(defn lfs-tap [#^Scheme scheme path-or-file]
  (Lfs. scheme (path path-or-file)))

(defn flow
  ([#^Map source-map #^Tap sink #^Pipe pipe]
   (flow nil {} source-map sink pipe))
  ([jar-path config #^Map source-map #^Tap sink #^Pipe pipe]
   (let [props (Properties.)]
     (when jar-path
       (FlowConnector/setApplicationJarPath props jar-path))
     (doseq [[k v] config]
       (.setProperty props k v))
     (.setProperty props "mapred.used.genericoptionsparser" "true")
     (let [flow-connector (FlowConnector. props)]
       (.connect flow-connector source-map sink pipe)))))

(defn write-dot [#^Flow flow #^String path]
  (.writeDOT flow path))

(defn exec [#^Flow flow]
  (try
    (doto flow .start .complete)
    (catch cascading.flow.PlannerException e
      (.writeDOT e "exception.dot")
      (throw (RuntimeException.
        "see exception.dot to visualize your broken plan." e)))))

