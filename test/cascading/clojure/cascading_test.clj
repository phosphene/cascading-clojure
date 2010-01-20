(ns cascading.clojure.cascading-test
  (:require [cascading.clojure function_bootstrap])
  (:import [cascading.clojure FunctionBootstrap])
  (:use [cascading.clojure 
	  cascading
          taps
	  pipes
          io
          taps
	  function-filter-bootstrap])
   (:use [clojure.contrib map-utils])
   (:use clojure.test)
   (:require [clojure.contrib.str-utils2 :as s])
   (:import [cascading.pipe Pipe Each]
	    [cascading.flow Flow FlowConnector]
	    [cascading.clojure
             FunctionBootstrap
             FunctionFilterBootstrapInClojure]
	    [cascading.tuple Fields]))

(defn split-line [line] 
  (let [data (s/split line #"\t")]
    (cond (= 3 (count data)) (list data)
	  :otherwise (list (conj data "dummycontent")))))

(defn identity-each [& line]
  [line])

(defn second-of-line [line]
  [[(second (s/split line #"\t"))]])

(defn filter-dummycontent-name [name id]
  (not (= "dummycontent" name)))

(def test-with-fields
  {:each {:using split-line :reader identity :writer str :outputFields ["name" "id" "content"]}
		:each {:using identity-each :reader identity :writer str :inputFields ["name" "id"] :outputFields ["name" "id"]}
		:filter {:using filter-dummycontent-name :reader identity :writer str :inputFields ["name" "id"] :outputFields ["name" "id"]}})
	
(def test-with-fields1
  {:each {:using split-line :reader identity :writer str :outputFields ["name" "id" "content"]}
		:each {:using identity-each :reader identity :writer str :inputFields ["name" "id"] :outputFields ["name" "id"]}
		:filter {:using filter-dummycontent-name :reader identity :writer str :inputFields ["name" "id"] :outputFields ["name1" "id1"]}})

(def wf1 {:each {:using identity :reader identity :writer str :outputFields ["name" "id" "content"]}
		:each {:using identity :reader identity :writer str :inputFields ["name" "id"] :outputFields ["name" "id"]}
		:filter {:using (constantly true) :reader identity :writer str :inputFields ["name" "id"] :outputFields ["name1" "id1"]}})

(def sample-join
  {:wfs [test-with-fields test-with-fields1] 
   :groupFields [["id"] ["id1"]] ;fields
   :using (fn [id name id1 name1] [id name id1 name1])
   :outputFields ["id" "name" "id1" "name1"]
   :wftype :join})

(deftest mk-pipe-test
  (let [p (mk-pipe "test" "dummy-ns" test-with-fields)]
    (is (= (Fields. (into-array String ["name" "id"])) (.getFieldDeclaration p)))))

(deftest build-workflow-from-symbol
  (let [wf (workflow "in" "out" #'test-with-fields)]
    (is (= Flow (class wf)))))

(deftest make-simple-each
  (with-tmp-files [in (temp-dir "source")
		   out (temp-path "sink")]
     (write-lines-in in "some.data" [1])
     (let [props (configure-properties FunctionBootstrap)
           e (Each. "simple"
                 (fields [0])
                 (FunctionBootstrap.
                  (fields [0])
                  (fields [0])
                  read-string
                  pr-str
                  inc
                  single-val-callback
                  (str (ns-name *ns*))))
        inced (.openSink
               ( execute (flow props (test-tap in) (test-tap out) e)))]
    (is (= 2 (read-tuple (.next inced)))))))

(deftest build-join-from-symbol
  (let [wf (workflow ["in1" "in2"] "out" #'sample-join)
	ops (.getAllOperations 
			  (first (.getSteps wf)))
	filter-ops (filter 
		 #(= FunctionFilterBootstrapInClojure (class %))
		 ops)
	tapmap (.getSources wf)]

    ;;there are two incoming sources
    (is (= 2 (count tapmap)))

    ;;the function filters are for "id" and "id1"
    (let [fields (into #{}
		       (map 
		  #(.print (.getFieldDeclaration %)) 
		  filter-ops))]
    (is (contains? fields "['name1', 'id1']"))
    (is (contains? fields "['name', 'id']"))) 

    (is (= 2 (count filter-ops)))
    (is (= 6 (count ops)))))

(def classifier-example
  {:groupBy {:using (fn [x] [:a (apply + x)])
	     :reader read-string
	     :writer pr-str 
	     :outputFields ["key" "second"]}
   :everygroup {:using +
		:reader read-string 
		:init (fn [] [[0 0]]) 
		:writer str 
		:inputFields ["second"]}})

(def foo 10)

(deftest get-ns-and-name-from-symbol
  (is (= ['cascading.clojure.cascading-test 'foo] (var-symbols #'foo))))
