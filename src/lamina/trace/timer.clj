;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.trace.timer
  (:use
    [potemkin]
    [flatland.useful.datatypes :only (make-record)]
    [lamina.core.utils :only (enqueue)])
  (:require
    [clojure.string :as str]
    [lamina.trace.context :as trace-context]
    [lamina.trace.probe :as p]
    [lamina.core.result :as r]
    [lamina.core.context :as context]
    [lamina.executor.utils :as ex])
  (:import
    [java.io
     Writer]
    [java.util.concurrent
     ConcurrentLinkedQueue]))



;;;

(defprotocol+ ITimed
  (timing [_ start])
  (mark-enter [_])
  (mark-error [_ err])
  (mark-waiting [_])
  (mark-return [_ val])
  (add-sub-task [_ timer]))

(defn dummy-timer [name enqueue-error?]
  (reify
    ITimed
    (timing [_ start]
      {})
    (mark-error [_ err]
      (when enqueue-error?
        (enqueue (p/error-probe-channel [name :error]) {:error err})))
    (mark-enter [_])
    (mark-waiting [_])
    (mark-return [_ _])
    (add-sub-task [_ _] )))

;;;

(defrecord EnqueuedTiming
  [name
   context
   ^long offset
   ^long timestamp
   ^long duration
   ^long enqueued-duration
   ^long compute-duration
   sub-tasks
   args
   result])

(defmacro make-timing [type root-start start & extras]
  `(let [root-start# ~root-start 
         start# ~start
         duration# (if (or
                         (= Long/MIN_VALUE ~'return)
                         (= Long/MIN_VALUE ~'enter))
                     -1
                     (unchecked-subtract (long ~'return) (long ~'enter)))
         timing# (make-record ~type
                   :name ~'name
                   :timestamp ~'timestamp
                   :context (trace-context/context)
                   :offset (unchecked-subtract (long start#) (long root-start#))
                   :compute-duration (if (= Long/MIN_VALUE ~'waiting)
                                       duration#
                                       (unchecked-subtract (long ~'waiting) (long ~'enter)))
                   :duration duration#
                   :args ~'args
                   :result ~'result
                   :sub-tasks (when-not (.isEmpty ~'sub-tasks)
                                (doall (map #(timing % root-start#) ~'sub-tasks)))
                   ~@extras)]
     (if-not (neg? ~'start-stage)
       (assoc timing# :start-stage ~'start-stage)
       timing#)))

(deftype+ EnqueuedTimer
  [executor
   capture
   name
   return-probe
   error-probe
   ^long start-stage
   ^long timestamp
   ^long enqueued
   args
   ^{:volatile-mutable true} result
   ^{:volatile-mutable true, :tag long} enter
   ^{:volatile-mutable true, :tag long} waiting
   ^{:volatile-mutable true, :tag long} return
   ^ConcurrentLinkedQueue sub-tasks]
  ITimed
  (timing [_ start]
    (make-timing EnqueuedTiming start enqueued
      :enqueued-duration (if (= Long/MIN_VALUE enter)
                           -1
                           (unchecked-subtract (long enter) (long enqueued)))))
  (add-sub-task [_ timer]
    (.add sub-tasks timer))
  (mark-enter [this]
    (set! enter (System/nanoTime)))
  (mark-error [this err]
    (set! return (System/nanoTime))
    (let [timing (assoc (timing this enter) :error err)]
      (enqueue
        (or error-probe (p/error-probe-channel [name :error]))
        timing)
      (ex/trace-error executor timing)))
  (mark-waiting [this]
    (set! waiting (System/nanoTime)))
  (mark-return [this val]
    (set! return (System/nanoTime))
    (case capture
      (:out :in-out) (set! result val)
      nil)
    (let [timing (make-timing EnqueuedTiming enqueued enqueued
                   :enqueued-duration (if (= Long/MIN_VALUE enter)
                                        -1
                                        (unchecked-subtract (long enter) (long enqueued))))]
      (when return-probe
        (enqueue return-probe timing))
      (ex/trace-return executor timing))))

(defn enqueued-timer-
  [executor
   capture
   name
   args
   return-probe
   error-probe
   start-stage
   implicit?
   enqueue-error?]
  (let [enabled? (and return-probe (p/probe-enabled? return-probe))
        parent (when (or enabled? implicit?)
                 (context/timer))]
    (if (or enabled? parent)
      (let [timer (EnqueuedTimer.
                    executor
                    capture
                    name
                    (when enabled? return-probe)
                    error-probe
                    start-stage
                    (System/currentTimeMillis)
                    (System/nanoTime)
                    (case capture
                      (:in :in-out) args
                      nil)
                    nil
                    Long/MIN_VALUE
                    Long/MIN_VALUE
                    Long/MIN_VALUE
                    (ConcurrentLinkedQueue.))]
        (when parent
          (add-sub-task parent timer))
        timer)
      (dummy-timer name enqueue-error?))))

(defmacro enqueued-timer
  [executor
   & {:keys [name
             capture
             args
             return-probe
             error-probe
             start-stage
             implicit?
             enqueue-error?]
      :or {implicit? false
           enqueue-error? true
           start-stage -1}}]
  `(enqueued-timer-
     ~executor
     ~capture
     ~name
     ~args
     ~return-probe
     ~error-probe
     ~start-stage
     ~implicit?
     ~enqueue-error?))

;;;

(defrecord Timing
  [name
   context
   ^long timestamp
   ^long offset
   ^long compute-duration
   ^long duration
   sub-tasks
   args
   result])

(deftype+ Timer
  [capture
   name
   return-probe
   error-probe
   ^long start-stage
   args
   ^{:volatile-mutable true} result
   ^long timestamp
   ^long enter
   ^{:volatile-mutable true, :tag long} waiting
   ^{:volatile-mutable true, :tag long} return
   ^ConcurrentLinkedQueue sub-tasks]
  ITimed
  (timing [_ start]
    (make-timing Timing start enter))
  (add-sub-task [_ timer]
    (.add sub-tasks timer))
  (mark-enter [_]
    )
  (mark-waiting [this]
    (set! waiting (System/nanoTime)))
  (mark-error [this err]
    (set! return (System/nanoTime))
    (enqueue
      (or error-probe (p/error-probe-channel [name :error]))
      (assoc (timing this enter) :error err)))
  (mark-return [this val]
    (set! return (System/nanoTime))
    (case capture
      (:out :in-out) (set! result val)
      nil)
    (when return-probe
      (enqueue return-probe
        (make-timing Timing enter enter)))))

(defn timer-
  [capture
   name
   args
   return-probe
   error-probe
   start-stage
   implicit?
   enqueue-error?]
  (let [enabled? (and return-probe (p/probe-enabled? return-probe))
        parent (when (or enabled? implicit?)
                 (context/timer))]
    (if (or enabled? parent)
      (let [timer (Timer.
                    capture
                    name
                    (when enabled? return-probe)
                    error-probe
                    start-stage
                    (case capture
                      (:in :in-out) args
                      nil)
                    nil
                    (System/currentTimeMillis)
                    (System/nanoTime)
                    Long/MIN_VALUE
                    Long/MIN_VALUE
                    (ConcurrentLinkedQueue.))]
        (when parent
          (add-sub-task parent timer))
        timer)
      (dummy-timer name enqueue-error?))))

(defmacro timer
  [& {:keys [name
             capture
             args
             return-probe
             error-probe
             start-stage
             implicit?
             enqueue-error?]
      :or {implicit? false
           enqueue-error? true
           start-stage -1}}]
  `(timer-
     ~capture
     ~name
     ~args
     ~return-probe
     ~error-probe
     ~start-stage
     ~implicit?
     ~enqueue-error?))

;;;

(defn indent [n s]
  (->> s
    str/split-lines
    (map #(str "  " %))
    (interpose "\n")
    (apply str)))

(def durations
  {"ns" 1
   "us" 1e3
   "ms" 1e6
   "s" 1e9})

(defn duration [n scale]
  (str
    (format "%.1f" (float (/ n (durations scale))))
    scale))

(defn format-duration [n]
  (cond
    (< n 1e3) (duration n "ns")
    (< n 1e6) (duration n "us")
    (< n 1e9) (duration n "ms")
    :else (duration n "s")))

(defn format-timing [t]
  (let [desc (:name t)
        desc (if (instance? clojure.lang.Named desc)
               (name desc)
               (str desc))
        duration (:duration t)
        compute (:compute-duration t)
        enqueued (:enqueued-duration t)]
    (str/trim
      (str desc
        " - "
        (if duration
          (format-duration (+ (or enqueued 0) duration))
          "incomplete")
        (when (or enqueued (not= compute duration))
          " - ")
        (when enqueued
          (str "enqueued for " (format-duration enqueued)))
        (when (not= compute duration)
          (str
            (when enqueued ", ")
            "computed for " (format-duration compute)
            (when duration
              (str ", waited for " (format-duration (- duration compute))))))
        "\n"
        (indent 2
          (->> t
            :sub-tasks
            (map format-timing)
            (interpose "\n")
            (apply str)))))))


;;;

(defmethod print-method Timing [o ^Writer w]
  (.write w (pr-str (into {} o))))

(defmethod print-method EnqueuedTiming [o ^Writer w]
  (.write w (pr-str (into {} o))))
