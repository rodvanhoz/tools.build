;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build
  (:require
    [clojure.java.io :as jio]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.deps.alpha.reader :as reader]
    [clojure.tools.deps.alpha.util.dir :as dir]
    [clojure.tools.build.file :as file]))

(defn resolve-alias
  [basis key]
  (if (keyword? key)
    (-> basis :aliases key)
    key))

(defn- resolve-task
  [task-sym]
  (let [task-fn (if (qualified-symbol? task-sym)
                  (requiring-resolve task-sym)
                  (resolve (symbol "clojure.tools.build.tasks" (str task-sym))))]
    (if task-fn
      task-fn
      (throw (ex-info (str "Unknown task: " task-sym) {})))))

(defn- load-basis
  [project-deps]
  (let [{:keys [install-edn user-edn project-edn]} (reader/find-edn-maps)
        project (if project-deps
                  (reader/slurp-deps project-deps)
                  project-edn)
        edns [install-edn user-edn project]
        hash-file (jio/file ".cpcache" "build" (str (hash edns) ".basis"))]
    (if (.exists hash-file)
      (reader/slurp-deps hash-file)
      (let [master-edn (deps/merge-edns edns)
            basis (deps/calc-basis master-edn)]
        (.mkdirs (jio/file ".cpcache/build"))
        (spit hash-file basis)
        basis))))

(defn build
  "Execute build:
     Load basis using project-deps (default=./deps.edn)
     Load build params - either a map or an alias
     Run tasks - task may have an arg map or alias, which is merged into the build params"
  [{:keys [project-deps params tasks]}]
  (let [;{:keys [install-edn user-edn project-edn]} (reader/find-edn-maps)
        ;project (if project-deps
        ;          (reader/slurp-deps project-deps)
        ;          project-edn)
        ;ordered-edns (remove nil? [install-edn user-edn project])
        ;master-edn (deps/merge-edns ordered-edns)
        ;basis (deps/calc-basis master-edn)
        basis (load-basis project-deps)
        default-params (resolve-alias basis params)
        from-dir (if project-deps (.getParentFile (jio/file project-deps)) (jio/file "."))]
    (require 'clojure.tools.build.tasks)
    (dir/with-dir from-dir
      (reduce
        (fn [flow [task-sym args]]
          (let [begin (System/currentTimeMillis)
                task-fn (resolve-task task-sym)
                arg-data (merge default-params (resolve-alias basis args) flow)
                res (task-fn basis arg-data)
                end (System/currentTimeMillis)]
            (println "Ran" task-sym "in" (- end begin) "ms")
            (if-let [err (:error res)]
              (do
                (println "Error in" task-sym)
                (throw (ex-info err {:task task-sym, :arg-data arg-data})))
              (merge flow res))))
        nil
        tasks))
    (println "Done!")))

(comment
  (require
    '[clojure.tools.build.tasks :refer :all]
    '[clojure.tools.build.extra :refer :all])

  ;; Given aliases:
  ;; :clj-paths ["src/main/clojure"]
  ;  :java-paths ["java" "src/main/java"]
  ;  :resource-paths ["resources"]

  ;; clojure source lib
  (build
    '{:tasks [[clean] [sync-pom] [include-resources] [jar]]
      :params {:build/target-dir "target1"
               :build/class-dir "target1/classes"
               :build/resources :clj-paths
               :build/src-pom "pom.xml"
               :build/lib my/lib1
               :build/version "1.2.3"}})

  ;; clojure source lib with git version template
  (build
    '{:tasks [[clean] [clojure.tools.build.extra/git-version] [sync-pom] [include-resources] [jar]]
      :params {:build/target-dir "target2"
               :build/class-dir "target2/classes"
               :build/resources :resource-paths
               :build/src-pom "pom.xml"
               :git-version/template "0.8.%s"
               :git-version/version> :flow/version
               :build/lib my/lib2
               :build/version :flow/version}})

  ;; java executable jar (no clojure!)
  (build
    '{:tasks [[clean] [javac] [sync-pom] [include-resources] [jar]]
      :params {:build/target-dir "target3"
               :build/class-dir "target3/classes"
               :build/java-paths :java-paths ; ["java" "src/main/java"]
               :build/javac-opts ["-source" "8" "-target" "8"]
               :build/resources :resource-paths ; ["resources"]
               :build/src-pom "pom.xml"
               :build/lib org.clojure/tools.build
               :build/version "0.1.0"
               :build/main-class clojure.tools.build.demo}})

  ;; compiled clojure lib jar w/metadata elided
  (build
    '{:tasks [[clean] [compile-clj] [include-resources] [jar]]
      :params {:build/target-dir "target4lib"
               :build/class-dir "target4lib/classes"
               :build/clj-paths :clj-paths ; ["src"]
               :build/filter-nses [clojure.tools.build]
               :build/compiler-opts {:elide-meta [:doc :file :line]}
               :build/resources :resource-paths ; ["resources"]
               :build/src-pom "pom.xml"
               :build/lib org.clojure/tools.build
               :build/version "0.1.0"}})

  ;; compiled clojure app jar
  (build
    '{:tasks [[clean] [compile-clj] [include-resources] [jar]]
      :params {:build/target-dir "target4"
               :build/class-dir "target4/classes"
               :build/clj-paths :clj-paths ; ["src"]
               :build/resources :resource-paths ; ["resources"]
               :build/src-pom "pom.xml"
               :build/lib org.clojure/tools.build
               :build/version "0.1.0"
               :build/main-class clojure.tools.build.demo}})

  ;; uber compiled jar
  (build
    '{:tasks [[clean] [sync-pom] [compile-clj] [uber]]
      :params {:build/target-dir "target5"
               :build/class-dir "target5/classes"
               :build/clj-paths :clj-paths
               :build/src-pom "pom.xml"
               :build/lib my/lib1
               :build/version "1.2.3"}})

  ;; uber src jar
  (build
    '{:project-deps "uber-demo/deps.edn"
      :tasks [[clean] [sync-pom] [include-resources] [uber]]
      :params {:build/target-dir "uber-demo/target"
               :build/class-dir "uber-demo/target/classes"
               :build/resources ["uber-demo/src"]
               :build/src-pom "uber-demo/pom.xml"
               :build/lib my/lib1
               :build/version "1.2.3"}})

  ;; compiled lib
  (build
    '{:tasks [[clean] [clojure.tools.build.extra/git-version] [sync-pom] [compile-clj] [jar]]
      :params {:build/target-dir "target6"
               :build/class-dir "target6/classes"
               :build/src-pom "pom.xml"
               :build/lib org.clojure/tools.build
               :build/classifier "aot"
               :git-version/template "0.8.%s"
               :git-version/version> :flow/version
               :build/version :flow/version
               :build/clj-paths :clj-paths}})

  )
