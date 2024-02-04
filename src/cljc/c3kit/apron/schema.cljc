(ns c3kit.apron.schema
  "Defines data structure, coerces, validates."
  (:refer-clojure :exclude [uri?])
  (:require
    [c3kit.apron.corec :as ccc]
    [clojure.edn :as edn]
    [clojure.string :as str]
    #?(:cljs [com.cognitect.transit.types])                 ;; https://github.com/cognitect/transit-cljs/issues/41
    [clojure.walk :as walk]))

(comment
  "Schema Sample"
  {:field
   {:type        :string                                    ;; see type-validators for list
    :db          [:unique-value]                            ;; passed to database
    :coerce      [#(str % "y")]                             ;; single/list of coerce fns
    :validate    [#(> (count %) 1)]                         ;; single/list of validation fns
    :message     "message describing the field"             ;; coerce failure message (or :validate failure message)
    :validations [{:validate fn :message "msg"}]            ;; multiple validation/message pairs
    :present     [#(str %)]                                 ;; single/list of presentation fns
    }})

(def stdex
  #?(:clj  clojure.lang.ExceptionInfo
     :cljs cljs.core/ExceptionInfo))

(defn coerce-ex [v type]
  (let [value-str (pr-str v)
        value-str (if (< 50 (count value-str))
                    (str (subs value-str 0 45) "...")
                    value-str)]
    (ex-info (str "can't coerce " value-str " to " type) {:coerce? true :value v :type type})))

(def date #?(:clj java.util.Date :cljs js/Date))

;; region ----- Common Validations -----

(defn present? [v]
  (not (or (nil? v)
           (and (string? v) (str/blank? v)))))

(defn nil-or [f] (some-fn nil? f))

(def email-pattern #"[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")

(defn email? [value] (boolean (re-matches email-pattern value)))

(defn bigdec? [v] #?(:clj (instance? BigDecimal v) :cljs (number? v)))

(defn uri? [value]
  #?(:clj  (instance? java.net.URI value)
     :cljs (string? value)))

(defn is-enum? [enum]
  (let [enum-name (name (:enum enum))
        enum-set  (ccc/map-set #(keyword enum-name (name %)) (:values enum))]
    (fn [value]
      (or (nil? value)
          (contains? enum-set value)))))

;; endregion ^^^^^ Common Validations ^^^^^
;; region ----- Common Coercions -----

#?(:cljs
   (defn parse! [f v]
     (let [result (f v)]
       (if (js/isNaN result)
         (throw (js/Error "parsed NaN"))
         result))))

(defn ->boolean [value]
  (cond (nil? value) nil
        (boolean? value) value
        (string? value) (not= "false" (str/lower-case value))
        :else (boolean value)))

(defn ->string [value] (some-> value str))
(defn str-or-nil [v] (->string v))

(defn ->keyword [value]
  (cond
    (nil? value) nil
    (keyword? value) value
    :else (let [s (str value)]
            (if (str/starts-with? s ":")
              (keyword (subs s 1))
              (keyword s)))))

(defn ->float [v]
  (cond
    (nil? v) nil
    (string? v) (when-not (str/blank? v)
                  (try
                    #?(:clj (Double/parseDouble v) :cljs (parse! js/parseFloat v))
                    (catch #?(:clj Exception :cljs :default) _
                      (throw (coerce-ex v "float")))))
    #?@(:clj [(char? v) (-> v str ->float)])
    #?@(:cljs [(js/isNaN v) nil])
    (integer? v) (double v)
    (#?(:clj float? :cljs number?) v) v
    (bigdec? v) #?(:clj (.doubleValue v) :cljs v)
    :else (throw (coerce-ex v "float"))))

(defn ->int [v]
  (cond
    (nil? v) nil
    (string? v) (when-not (str/blank? v)
                  (try
                    #?(:clj  (long (Double/parseDouble v))
                       :cljs (parse! js/parseInt v))
                    (catch #?(:clj Exception :cljs :default) _
                      (throw (coerce-ex v "int")))))
    (keyword? v) (throw (coerce-ex v "int"))
    #?@(:clj [(char? v) (-> v str ->int)])
    #?@(:cljs [(js/isNaN v) nil])
    (integer? v) v
    (#?(:clj float? :cljs number?) v) (long v)
    (bigdec? v) #?(:clj (.intValue v) :cljs v)
    :else (throw (coerce-ex v "int"))))

(defn ->bigdec [v]
  (cond
    (nil? v) nil
    (string? v) (when-not (str/blank? v)
                  (try
                    #?(:clj  (bigdec v)
                       :cljs (parse! js/parseFloat v))
                    (catch #?(:clj Exception :cljs :default) _
                      (throw (coerce-ex v "bigdec")))))
    #?@(:clj [(char? v) (-> v str ->bigdec)])
    #?@(:cljs [(js/isNaN v) nil])
    (integer? v) #?(:clj (bigdec v) :cljs (double v))
    (#?(:clj float? :cljs number?) v) #?(:clj (bigdec v) :cljs v)
    #?(:clj (bigdec? v)) #?(:clj v)
    :else (throw (coerce-ex v "bigdec"))))

(defn ->date [v]
  (cond
    (nil? v) nil
    (instance? date v) v
    (integer? v) (doto (new #?(:clj java.util.Date :cljs js/Date)) (.setTime v))
    #?(:cljs (instance? goog.date.Date v)) #?(:cljs (js/Date. (.getTime v)))
    (string? v) (cond
                  (str/blank? v) nil
                  (str/starts-with? v "#inst") (edn/read-string v)
                  :else (throw (coerce-ex v "date")))
    :else (throw (coerce-ex v "date"))))

(defn ->sql-date [v]
  (cond
    (nil? v) nil
    (instance? #?(:clj java.sql.Date :cljs js/Date) v) v
    #?(:clj (instance? java.util.Date v)) #?(:clj (java.sql.Date. (.getTime v)))
    (integer? v) #?(:clj (java.sql.Date. v) :cljs (doto (new js/Date) (.setTime v)))
    #?(:cljs (instance? goog.date.Date v)) #?(:cljs (js/Date. (.getTime v)))
    (string? v) (cond
                  (str/blank? v) nil
                  (str/starts-with? v "#inst") #?(:clj (java.sql.Date. (.getTime (edn/read-string v))) :cljs (edn/read-string v))
                  :else (throw (coerce-ex v "sql-date")))
    :else (throw (coerce-ex v "sql-date"))))

(defn ->timestamp [v]
  (cond
    (nil? v) nil
    (instance? #?(:clj java.sql.Timestamp :cljs js/Date) v) v
    #?(:clj (instance? java.util.Date v)) #?(:clj (java.sql.Timestamp. (.getTime v)))
    (integer? v) #?(:clj (java.sql.Timestamp. v) :cljs (doto (new js/Date) (.setTime v)))
    #?(:cljs (instance? goog.date.Date v)) #?(:cljs (js/Date. (.getTime v)))
    (string? v) (cond
                  (str/blank? v) nil
                  (str/starts-with? v "#inst") #?(:clj (java.sql.Timestamp. (.getTime (edn/read-string v))) :cljs (edn/read-string v))
                  :else (throw (coerce-ex v "timestamp")))
    :else (throw (coerce-ex v "timestamp"))))

(defn ->uri [v]
  (cond
    (nil? v) nil
    #?@(:clj [(instance? java.net.URI v) v])
    (string? v) #?(:clj (java.net.URI/create v) :cljs v)
    :else (throw (coerce-ex v "uri"))))


;; MDM : https://github.com/cognitect/transit-cljs/issues/41
#?(:cljs (extend-type com.cognitect.transit.types/UUID IUUID))

(defn ->uuid [v]
  (cond
    (nil? v) nil
    (uuid? v) v
    (string? v) #?(:clj (java.util.UUID/fromString v) :cljs (uuid v))
    :else (throw (coerce-ex v "uuid"))))

;; endregion ^^^^^ Common Coercions ^^^^^
;; region ----- Type Tables -----

(def type-validators
  {:bigdec    (nil-or bigdec?)
   :boolean   (nil-or boolean?)
   :double    (nil-or #?(:clj float? :cljs number?))
   :float     (nil-or #?(:clj float? :cljs number?))
   :instant   (nil-or #(instance? date %))
   :date      (nil-or #?(:clj #(instance? java.sql.Date %) :cljs #(instance? date %)))
   :timestamp (nil-or #?(:clj #(instance? java.sql.Timestamp %) :cljs #(instance? date %)))
   :int       (nil-or integer?)
   :keyword   (nil-or keyword?)
   :kw-ref    (nil-or keyword?)
   :long      (nil-or integer?)
   :ref       (nil-or integer?)
   :string    (nil-or string?)
   :uri       (nil-or uri?)
   :uuid      (nil-or uuid?)
   :ignore    (constantly true)
   :schema    (nil-or map?)})

(def type-coercers
  {:bigdec    ->bigdec
   :boolean   ->boolean
   :double    ->float
   :float     ->float
   :instant   ->date
   :date      ->sql-date
   :timestamp ->timestamp
   :int       ->int
   :keyword   ->keyword
   :kw-ref    ->keyword
   :long      ->int
   :ref       ->int
   :string    ->string
   :uri       ->uri
   :uuid      ->uuid
   :ignore    identity
   :schema    identity})

;; endregion ^^^^^ Type Tables ^^^^^
;; region ----- Common Schema Attributes -----

(def omit
  "Used as a :present value to remove the entry from presentation"
  (constantly nil))

(defn kind [key]
  {:type     :keyword
   :value    key
   :validate [#(or (nil? %) (= key %))]
   :coerce   [#(or % key)]
   :message  (str "mismatch; must be " key)})

(def id {:type :ref})

;; endregion ^^^^^ Common Schema Attributes ^^^^^
;; region ----- Processing -----

(defn- multiple? [thing]
  (or (sequential? thing)
      (set? thing)))

(defn- ->vec [v]
  (cond
    (nil? v) []
    (multiple? v) (vec v)
    :else [v]))

(defn ->seq [v]
  (cond
    (nil? v) []
    (multiple? v) v
    :else (list v)))

(defn type-coercer! [type]
  (or (get type-coercers type)
      (throw (ex-info (str "unhandled coercion type: " (pr-str type)) {:coerce? true}))))

(defn type-validator! [type]
  (or (get type-validators type)
      (throw (ex-info (str "unhandled validation type: " (pr-str type)) {}))))

(defn- validation-ex [message value] (ex-info "invalid" {:invalid? true :message (or message "is invalid") :value value}))

(defn error-exception
  "Return the exception attached to a FieldError if any, otherwise nil."
  [field-error]
  (-> field-error meta :exception))

(defn- error->exception [error]
  (ex-info (:message error) {}))

(defprotocol FieldError
  (as-exception [this])
  (error-message [this]))

(defrecord CoerceError [message]
  FieldError
  (as-exception [this] (error->exception this))
  (error-message [_] message)
  Object
  (toString [_] (str "Coerce FAILURE : " message)))

(defrecord ValidateError [message]
  FieldError
  (as-exception [this] (error->exception this))
  (error-message [_] message)
  Object
  (toString [_] (str "Validate FAILURE : " message)))

(defrecord PresentError [message]
  FieldError
  (as-exception [this] (error->exception this))
  (error-message [_] message)
  Object
  (toString [_] (str "Present FAILURE: " message)))

(defn- -create-field-error [ctor default-message options]
  (let [ex      (:exception options)
        message (or (:message options) (:message (ex-data ex)) (ex-message ex) default-message)]
    (with-meta (ctor message) (dissoc options :message))))

(defn field-error?
  "Returns true if the value is a FieldError, false otherwise."
  [value]
  #?(:clj  (instance? c3kit.apron.schema.FieldError value)
     :cljs (satisfies? FieldError value)))

(defn error-seq
  "Returns a sequence of all the FieldErrors in a processed entity."
  [entity]
  (cond (field-error? entity) [entity]
        (map? entity) (mapcat error-seq (vals entity))
        (multiple? entity) (mapcat error-seq entity)
        :else nil))

(defn error?
  "Return true if the processed entity has errors, false otherwise."
  [entity]                                                  ;; TODO - MDM: optimize, maybe store failure as metadata on entity?
  (not (empty? (error-seq entity))))

(defmulti -process-field (fn [process _spec _value] process))
(defmulti -process-entity (fn [process _key _spec _entity] process))
(defmulti -process-error (fn [process options] process))

(defmethod -process-error :coerce [_ options]
  (-create-field-error ->CoerceError "coercion failed" options))
(defmethod -process-error :validate [_ options]
  (-create-field-error ->ValidateError "is invalid" options))
(defmethod -process-error :present [_ options]
  (-create-field-error ->PresentError "present failed" options))

(defn- field-result-or-error [process spec value]
  (try
    (-process-field process spec value)
    (catch #?(:clj Exception :cljs :default) e
      (-process-error process {:exception e}))))

(defn- entity-result-or-error [process key spec entity]
  (try
    (-process-entity process key spec entity)
    (catch #?(:clj Exception :cljs :default) e
      (-process-error process {:exception e}))))

(defn- coerce-map [m]
  (cond (nil? m) m
        (map? m) m
        (sequential? m) (into {} m)
        :else (throw (coerce-ex m "map"))))

(defmethod -process-field :coerce [_ spec value]
  (let [{:keys [type message]} spec
        type-coercer (if (map? type) coerce-map (type-coercer! type))
        coerce-fns   (conj (->vec (:coerce spec)) type-coercer)]
    (try
      (reduce (fn [result coerce-fn] (coerce-fn result)) value coerce-fns)
      (catch #?(:clj Exception :cljs :default) e
        (-process-error :coerce {:message message :exception e})))))

(defn- process-validations [validations value]
  (doseq [{:keys [validate message]} validations]
    (let [validate-fns (if (multiple? validate) validate [validate])]
      (doseq [v-fn validate-fns]
        (when-not (v-fn value)
          (throw (validation-ex message value)))))))

(defmethod -process-field :validate [_ spec value]
  (let [{:keys [type validate validations message]} spec
        type-validator (if (map? type) (nil-or map?) (type-validator! type))
        validations    (concat [{:validate type-validator :message message}]
                               (if validate [{:validate validate :message message}] [])
                               validations)]
    (process-validations validations value)
    value))

(defmethod -process-field :conform [_ spec value]
  (let [coerce-result (field-result-or-error :coerce spec value)]
    (if (field-error? coerce-result)
      coerce-result
      (let [field-result-or-failure (field-result-or-error :validate spec coerce-result)]
        field-result-or-failure))))

(defmethod -process-field :present [_ spec value]
  (let [present-fns (->vec (:present spec))]
    (reduce (fn [result present-fn] (present-fn result)) value present-fns)))

(defmethod -process-entity :coerce [_ key spec entity]
  (let [{:keys [coerce message]} spec
        coerce-fns (->vec coerce)]
    (try
      (let [coerced-entity (reduce (fn [result coerce-fn] (assoc result key (coerce-fn result))) entity coerce-fns)]
        (get coerced-entity key))
      (catch #?(:clj Exception :cljs :default) e
        (-process-error :coerce {:message message :exception e})))))

(defmethod -process-entity :validate [_ key spec entity]
  (let [{:keys [validate validations message]} spec
        validations (concat (if validate [{:validate validate :message message}] [])
                            validations)]
    (process-validations validations entity)
    (get entity key)))

(defmethod -process-entity :conform [_ key spec entity]
  (let [coerce-result (entity-result-or-error :coerce key spec entity)]
    (if (field-error? coerce-result)
      coerce-result
      (entity-result-or-error :validate key spec (assoc entity key coerce-result)))))

(defmethod -process-entity :present [_ key spec entity]
  (let [present-fns      (->vec (:present spec))
        presented-entity (reduce (fn [result present-fn] (assoc result key (present-fn result))) entity present-fns)]
    (get presented-entity key)))

(declare process-entity)

(defn- -process-value [process spec value]
  (let [type   (:type spec)
        ?seq   (multiple? type)
        type   (if ?seq (first type) type)
        schema (when (map? type) type)]
    (if ?seq
      (cond (nil? value) nil
            (multiple? value) (let [spec   (assoc spec :type (if (some? schema) schema type))
                                    result (mapv #(-process-value process spec %) value)]
                                (if (= :present process)    ;; MDM - I know.  OCP violation.  Maybe better than multi-methods though.
                                  (ccc/removev nil? result)
                                  result))
            :else (-process-error process {:message (str "[" type "] expected")}))
      (if (and (some? schema) (map? value))
        (let [entity (process-entity process schema value)]
          (if (error? entity)
            entity
            (field-result-or-error process spec entity)))
        (field-result-or-error process spec value)))))

(defn- -process-spec [process entity [key spec]]
  (let [value     (get entity key)
        new-value (-process-value process spec value)]
    (if (some? new-value)
      (assoc entity key new-value)
      (dissoc entity key))))

(defn- -process-spec-on-entity [process entity [key spec]]
  (let [result (entity-result-or-error process key spec entity)]
    (if (some? result)
      (assoc entity key result)
      (dissoc entity key))))

(defn- process-entity [process schema entity]
  (let [entity (select-keys entity (keys schema))
        entity (reduce (partial -process-spec process) entity (dissoc schema :*))]
    (if (error? entity)
      entity
      (reduce (partial -process-spec-on-entity process) entity (:* schema)))))

(defn- process-value-or-error [process spec value]
  (let [result (-process-value process spec value)]
    (if-let [failure (first (error-seq result))]
      (throw (as-exception failure))
      result)))

;; endregion ^^^^^ Processing ^^^^^

;; region ----- API -----

(defn coerce-value
  "returns coerced value or throws an exception"
  ([schema key value] (coerce-value (get schema key) value))
  ([spec value] (process-value-or-error :coerce spec value)))

(defn validate-value!
  "throws an exception when validation fails, value otherwise"
  ([schema key value] (validate-value! (get schema key) value))
  ([spec value] (process-value-or-error :validate spec value)))

(defn valid-value?
  "return true or false"
  ([schema key value] (valid-value? (get schema key) value))
  ([spec value] (try (validate-value! spec value) true (catch #?(:clj Exception :cljs :default) _ false))))

(defn conform-value
  "coerce and validate, returns coerced value or throws"
  ([schema key value] (conform-value (get schema key) value))
  ([spec value] (process-value-or-error :conform spec value)))

(defn present-value
  "returns a presentable representation of the value"
  ([schema key value] (present-value (get schema key) value))
  ([spec value] (process-value-or-error :present spec value)))

(defn coerce
  "Returns coerced entity or SchemaError if any coercion failed. Use error? to check result.
  Use Case: 'I want to change my data into the types specified by the schema.'"
  [schema entity]
  (process-entity :coerce schema entity))

(defn validate
  "Returns entity with all values true, or SchemaError when one or more invalid fields. Use error? to check result.
  Use Case: 'I want to make sure all the data is valid according to the schema.'"
  [schema entity]
  (process-entity :validate schema entity))

(defn conform
  "Returns coerced entity or SchemaError upon any coercion or validation failure. Use error? to check result.
  Use Case: 'I want to coerce my data then validate the coerced data, all according to the schema.'
  Use Case: Data comes in from a web-form so strings have to be coerced into numbers, etc., then
            we need to validate that the data is good."
  [schema entity]
  (process-entity :conform schema entity))

(defn present
  "Returns presented entity with FieldErrors where the process failed. Use error? to check result."
  [schema entity]
  (process-entity :present schema entity))

(defn- as-map-or-nil [thing]
  (when (seq thing)
    (into {} thing)))

(defn error-map [result]
  (cond (field-error? result) result
        (map? result) (->> (map (fn [[k v]] (when-let [v (error-map v)] [k v])) result)
                           (remove nil?)
                           as-map-or-nil)
        (multiple? result) (->> (map-indexed (fn [k v] (when-let [v (error-map v)] [k v])) result)
                                (remove nil?)
                                as-map-or-nil)
        :else nil))

(defn message-map
  "nil when there are no errors, otherwise a map {<field> <error message>}."
  ([result]
   (when (error? result)
     (when-let [errors (error-map result)]
       (walk/postwalk (fn [v] (if (field-error? v) (error-message v) v)) errors)))))

(def ^{:doc "Same as message-map.  Exists for backwards compatibility."} error-message-map message-map)

(defn message-seq
  "seq of 'friendly' error messages; nil if none."
  [result]
  (when-let [errors (message-map result)]
    (loop [errors errors stack () path () result []]
      (if (empty? errors)
        (if (empty? stack)
          result
          (recur (first stack) (rest stack) (rest path) result))
        (let [[k v] (first errors)
              new-path (cons (if (int? k) (str k) (name k)) path)]
          (if (map? v)
            (recur v (conj stack (rest errors)) new-path result)
            (let [value (str (str/join "." (reverse new-path)) " " v)]
              (recur (rest errors) stack path (conj result value)))))))))

(def ^{:doc "Same as message-seq.  Exists for backwards compatibility."} messages message-seq)

(defn coerce-errors
  "Runs coerce on the entity and returns a map of error message, or nil if none."
  [schema entity]
  (message-map (coerce schema entity)))

(defn validation-errors
  "Runs validate on the entity and returns a map of error message, or nil if none."
  [schema entity]
  (message-map (validate schema entity)))

(defn conform-errors
  "Runs conform on the entity and returns a map of error message, or nil if none."
  [schema entity]
  (message-map (conform schema entity)))

(defn coerce!
  "Returns a coerced entity or throws an exception if there are errors."
  [schema entity]
  (let [result (coerce schema entity)]
    (if (error? result)
      (throw (ex-info "Uncoercable entity" result))
      result)))

(defn validate!
  "Returns a validated entity or throws an exception if there are errors."
  [schema entity]
  (let [result (validate schema entity)]
    (if (error? result)
      (throw (ex-info "Invalid entity" result))
      result)))

(defn conform!
  "Returns a conformed entity or throws an exception if there are errors."
  [schema entity]
  (let [result (conform schema entity)]
    (if (error? result)
      (throw (ex-info "Unconformable entity" result))
      result)))

(defn present!
  "Returns a presented entity or throws an exception if there are errors."
  [schema entity]
  (let [result (present schema entity)]
    (if (error? result)
      (throw (ex-info "Unpresentable entity" result))
      result)))

;; endregion ^^^^^ API ^^^^^

;; region ----- Merging Schemas -----

(defn- validate->validations [{:keys [validate message] :as spec}]
  (if validate
    (-> (dissoc spec :validate)
        (update :validations ccc/conjv {:validate validate :message message}))
    spec))

(defn merge-specs [a b]
  (let [a (validate->validations a)
        b (validate->validations b)]
    (if-let [validations (seq (concat (:validations a []) (:validations b [])))]
      (assoc (merge a b) :validations (vec validations))
      (merge a b))))

(defn merge-schemas [& schemas]
  (let [entity-specs (apply merge-with merge-specs (map :* schemas))
        attr-specs   (apply merge-with merge-specs (map #(dissoc % :*) schemas))]
    (if (seq entity-specs)
      (assoc attr-specs :* entity-specs)
      attr-specs)))

;; endregion ^^^^^ Merging Schemas ^^^^^
