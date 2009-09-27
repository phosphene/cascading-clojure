(ns org.parsimonygroup.tests
  (:use [clojure.contrib.test-is :only (run-tests)])
  (:gen-class))

(def test-names [:workflow-structs-test
		 :makemain-utils-test
		 :cascading-test]) 

(def test-namespaces
     (map #(symbol (str "org.parsimonygroup." (name %)))
          test-names))

(defn run
  "Runs all defined tests"
  []
  (println "Loading tests...")
  (apply require :reload-all test-namespaces)
  (apply run-tests test-namespaces))

(defn -main
  "Run all defined tests from the command line"
  [& args]
  (run)
  (System/exit 0))