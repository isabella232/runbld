(ns runbld.notifications.email
  (:refer-clojure :exclude [send])
  (:require [runbld.schema :refer :all]
            [schema.core :as s])
  (:require [clojure.string :as str]
            [postal.core :as mail]
            [runbld.store :as store]
            [runbld.io :as io]
            [runbld.notifications :as n]
            [runbld.vcs :as vcs]
            [stencil.core :as mustache]))

(defn split-addr [s]
  (vec
   (cond
     (and
      (string? s)
      (.contains s ",")) (->> (str/split s #",")
                              (map #(.trim %)))
     (string? s) [s]
     (sequential? s) s)))

(defn entropy []
  (->> (java.util.UUID/randomUUID)
       str
       (take 4)
       (apply str)
       .toUpperCase))

(s/defn attach-failure :- {(s/required-key :type) s/Keyword
                           (s/required-key :content) java.io.File
                           (s/required-key :content-type) s/Str}
  [failure :- Failure]
  (let [basename (java.net.URLEncoder/encode
                  (format "%s-%s-%s-%s.txt"
                          (:build-id failure)
                          (:class failure)
                          (:test failure)
                          (entropy)))
        f (io/file "/tmp" basename)]
    (spit f (:stacktrace failure))
    {:type :attachment
     :content f
     :content-type "text/plain"}))

(s/defn send* :- s/Any
  [conn     :- {s/Keyword s/Any}
   from     :- s/Str
   to       :- [s/Str]
   subject  :- s/Str
   plain    :- s/Str
   html     :- (s/maybe s/Str)
   failures :- [Failure]]
  (let [failure-attachments (map attach-failure failures)
        body (if html
               [{:type "text/html; charset=utf-8"
                 :content html}]
               [{:type "text/plain; charset=utf-8"
                 :content plain}])
        body (concat body failure-attachments)]
    (mail/send-message
     conn
     {:from from
      :to to
      :subject subject
      :body body})))

(s/defn obfuscate-addr :- s/Str
  [addr :- s/Str]
  (str/replace addr
               (re-pattern "(.*?)@([^.]+\\.)?(.)[^.]+\\.([^.]+)$")
               "$1@$3***.$4"))

(s/defn send :- s/Any
  [opts :- MainOpts
   ctx :- EmailCtx]
  ;; If needing to regenerate for render tests
  #_(spit "test/context.edn"
          (with-out-str
            (clojure.pprint/pprint
             (into (sorted-map) ctx))))
  (let [rcpts (split-addr (-> opts :email :to))]
    ((opts :logger) "MAILING:" (str/join ", "
                                         (map obfuscate-addr rcpts)))
    (send* (opts :email)
           (-> opts :email :from)
           rcpts
           (-> ctx :email :subject)
           (mustache/render-string
            (slurp
             (io/resolve-resource
              (-> opts :email :template-txt)))
            ctx)
           (when (and (-> opts :email :template-html)
                      (not (-> opts :email :text-only)))
             (mustache/render-string
              (slurp
               (io/resolve-resource
                (-> opts :email :template-html)))
              ctx))
           (:failures ctx))))

(s/defn make-context :- EmailCtx
  [opts build failure]
  (-> (n/make-context opts build failure)
      (update-in [:email :to] #(str/join ", " %))
      (assoc-in [:email :subject]
                (format "%s %s %s"
                        (-> build :process :status)
                        (-> build :build :org-project-branch)
                        (-> build :vcs :commit-short)))))

(s/defn send? :- s/Bool
  [build :- StoredBuild]
  (pos?
   (-> build :process :exit-code)))

(defn maybe-send! [opts {:keys [index type id] :as addr}]
  (let [build-doc (store/get (-> opts :es :conn) addr)
        failure-docs (store/get-failures opts (:id build-doc))]
    (if (send? build-doc)
      (let [ctx (make-context opts build-doc failure-docs)]
        (send opts ctx))
      ((opts :logger) "NO MAIL GENERATED"))))
