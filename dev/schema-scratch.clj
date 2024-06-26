;; Scratch file used for documenting the schema library in SCHEMA.md.

{:kind :point
 :x    1
 :y    2}

{:kind  :line
 :start {:x 1 :y 2}
 :end   {:x 5 :y 6}}

{:kind   :circle
 :center {:x 1 :y 1}
 :radius 5}

{:kind   :polygon
 :points [{:x 1 :y 1} {:x 2 :y 2} {:x 1 :y 2} {:x 1 :y 1}]}

(def point {:kind {:type :keyword}
            :x    {:type :int}
            :y    {:type :int}})

(def line {:kind  {:type :keyword}
           :start {:type :map :schema point}
           :end   {:type :map :schema point}})

(def circle {:kind   {:type :keyword}
             :center {:type :map :schema point}
             :radius {:type :int}})

(def polygon {:kind   {:type :keyword}
              :points {:type        :seq
                       :spec        {:type :map :schema point}
                       :validations [{:validate #(>= 4 (count %)) :message "must have at least 4 points"}
                                     {:validate #(= (first %) (last %)) :message "not closed"}]}})

(def data {:kind :point
           :x    "1"
           :y    "2"})

;; 1
(require '[c3kit.apron.schema :as schema])
(schema/coerce point data)


;; 2
(def data {:kind :point
           :x    ["1"]
           :y    ["2"]})
(schema/coerce point data)

;; 3
(def point {:kind {:type :keyword}
            :x    {:type :int :coerce (fn [v] (schema/->int (first v)))}
            :y    {:type :int :coerce first}})
(schema/coerce point data)

;; Validation

(def data1 {:kind :point
            :x    1
            :y    2})
(def data2 {:kind :point
            :x    "1"
            :y    "2"})

(schema/validate point data1)
(schema/validate point data2)

;; with message
(def point {:kind {:type :keyword}
            :x    {:type :int :message "must be an int"}
            :y    {:type :int :message "must be an int"}})
(schema/validate point data2)

;; more restrictive
(def point {:kind {:type :keyword}
            :x    {:type     :int
                   :message  "must be an even int"
                   :validate even?}
            :y    {:type     :int
                   :message  "must be an odd int"
                   :validate odd?}})
(schema/validate point {:kind :point :x "2" :y "1"})
(schema/validate point {:kind :point :x 1 :y 2})

;; multiple validations
(def point {:kind {:type :keyword}
            :x    {:type        :int
                   :message     "must be an int"
                   :validations [{:validate even? :message "must be even"}
                                 {:validate #(<= 0 % 100) :message "out of range"}]}
            :y    {:type        :int
                   :message     "must be an int"
                   :validations [{:validate odd? :message "must be odd"}
                                 {:validate #(<= 0 % 100) :message "out of range"}]}})
(schema/validate point {:kind :point :x "101" :y "102"})
(schema/validate point {:kind :point :x 1 :y 2})
(schema/validate point {:kind :point :x 102 :y 101})

;; Conform

(schema/conform point {:kind :point :x "2" :y "1"})
(schema/conform point {:kind :point :x "blah" :y "2"})

;; Present

(schema/present point {:kind :point :x 1 :y 2})

(def point {:kind {:type :keyword}
            :x    {:type :int :present #(str "X=" %)}
            :y    {:type :int :present #(str "Y=" %)}})
(schema/present point {:kind :point :x 1 :y 2})

;; entity level specs

(defn square [n] (* n n))
(defn distance [point] (Math/sqrt (+ (square (:x point)) (square (:y point)))))
(def point {:kind {:type :keyword}
            :x    {:type :int}
            :y    {:type :int}
            :*    {:distance {:coerce   distance
                              :validate #(>= (distance %) 5)
                              :message  "too close to origin"}}})
(schema/coerce point {:kind :point :x 1 :y 2})
(schema/validate point {:kind :point :x 1 :y 2})
(schema/coerce point {:kind :point :x 4 :y 4})
(schema/validate point {:kind :point :x 4 :y 4})

;; nested

(def point {:kind {:type :keyword}
            :x    {:type :int}
            :y    {:type :int}})

(def line {:kind  {:type :keyword}
           :start {:type :map :schema point}
           :end   {:type :map :schema point}})

(schema/conform line {:kind  :line
                      :start {:kind :point :x "1" :y "2"}
                      :end   {:kind :point :x 3.45 :y 6.78}})

;; polygon

(def polygon {:kind   {:type :keyword}
              :points {:type        :seq
                       :spec        {:type :map :schema point}
                       :validations [{:validate #(>= (count %) 4) :message "must have at least 4 points"}
                                     {:validate #(= (first %) (last %)) :message "not closed"}]}})

(schema/conform polygon {:kind :polygon})
(schema/conform polygon {:kind   :polygon
                         :points [{:kind :point :x "1" :y "2"}
                                  {:kind :point :x 3.45 :y 6.78}
                                  {:kind :point :x 6 :y 4}
                                  {:kind :point :x 99 :y 99}]})
(schema/conform polygon {:kind   :polygon
                         :points [{:kind :point :x "1" :y "2"}
                                  {:kind :point :x 3.45 :y 6.78}
                                  {:kind :point :x 6 :y 4}
                                  {:kind :point :x 1 :y 2}]})

;; one of

(def point {:kind (schema/kind :point)
            :x    {:type :int}
            :y    {:type :int}})

(def line {:kind  (schema/kind :line)
           :start {:type :map :schema point}
           :end   {:type :map :schema point}})

(def circle {:kind   (schema/kind :circle)
             :center {:type :map :schema point}
             :radius {:type :int}})

(def geometry {:kind     (schema/kind :geometry)
               :geometry {:type :one-of :specs [{:type :map :schema point}
                                                {:type :map :schema line}
                                                {:type :map :schema circle}]}})

(schema/conform geometry {:kind :geometry :geometry {:kind :point :x "1" :y "2"}})
(schema/conform geometry {:kind :geometry :geometry {:kind  :line
                                                     :start {:kind :point :x "1" :y "2"}
                                                     :end   {:kind :point :x 3.45 :y 6.78}}})
(schema/conform geometry {:kind :geometry :geometry {:kind   :circle
                                                     :center {:kind :point :x "1" :y "2"}
                                                     :radius 42}})
(schema/conform geometry {:kind :geometry :geometry {:kind :squiggle}})

;; errors

(schema/error? (schema/validate point {:kind :point :x 1 :y 2}))
(schema/error? (schema/validate point {:kind :point :x "blah" :y 2}))

(schema/message-map (schema/validate point {:kind :point :x 1 :y 2}))
(schema/message-map (schema/validate point {:kind :point :x "blah" :y 2}))
(schema/message-map (schema/conform line {:kind  :line
                                          :start {:kind :point :x "blah" :y "2"}
                                          :end   {:kind :point :x 3.45 :y "blah"}}))

(schema/message-seq (schema/validate point {:kind :point :x 1 :y 2}))
(schema/message-seq (schema/validate point {:kind :point :x "blah" :y 2}))
(schema/message-seq (schema/conform line {:kind  :line
                                          :start {:kind :point :x "blah" :y "2"}
                                          :end   {:kind :point :x 3.45 :y "blah"}}))

(schema/coerce-message-map point {:kind :point :x "blah" :y 2})
(schema/validate-message-map point {:kind :point :x "blah" :y 2})
(schema/conform-message-map point {:kind :point :x "blah" :y 2})

;; unspecified fields

(schema/coerce point {:kind :point :x 1 :y 2 :my-extra-data "goes bye bye"})

;; merging

(def point {:kind {:type :keyword}
            :x    {:type :int}
            :y    {:type :int}})

(schema/merge-schemas point {:x {:validate even? :message "must be even"}
                             :y {:validate odd? :message "must be odd"}})

;; schema validation

(schema/conform-schema! point)
(schema/conform-schema! {:foo {:type :blah}})

;; shorthands

(schema/normalize-spec {:type [:int] :validate even?})
(schema/normalize-spec {:type [{:type :int}] :validate seq})
(schema/normalize-spec {:type {:foo {:type :string}}})
(schema/normalize-spec {:type #{:string :int}})

(def line {:kind  {:type :keyword}
           :start {:type point}
           :end   {:type point}})

(def polygon {:kind   {:type :keyword}
              :points {:type [point]}})
