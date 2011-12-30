;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.core.result
  (:require
    [lamina.core.lock :as l])
  (:import
    [lamina.core.lock
     AsymmetricLock]
    [java.util.concurrent
     CopyOnWriteArrayList
     CountDownLatch]
    [java.util ArrayList]
    [java.io Writer]))

(set! *warn-on-reflection* true)

(deftype ResultCallback [original on-success on-error]
  Object
  (equals [this x]
    (and
      (instance? ResultCallback x)
      (or
        (identical? this x)
        (identical? original x)
        (identical? this (.original ^ResultCallback x)))))
  (hashCode [this]
    (if original
      (hash original)
      (System/identityHashCode this))))

(defprotocol Result
  (success [_ val])
  (error [_ err])
  (success! [_ val])
  (error! [_ err])
  (claim [_])
  (success-value [_ default-value])
  (error-value [_ default-value])
  (result [_])
  (subscribe [_ callback])
  (cancel-callback [_ callback]))

;;;

(deftype SuccessResult [value callback-modifier]
  clojure.lang.IDeref
  (deref [_] value)
  Result
  (success [_ _]
    :lamina/already-realized!)
  (success! [_ _]
    :lamina/already-realized!)
  (error [_ _]
    :lamina/already-realized!)
  (error! [_ _]
    :lamina/already-realized!)
  (claim [_]
    false)
  (success-value [_ _]
    value)
  (error-value [_ default-value]
    default-value)
  (result [_]
    :success)
  (subscribe [_ callback]
    ((callback-modifier (.on-success ^ResultCallback callback)) value))
  (cancel-callback [_ callback]
    false)
  (toString [_]
    (str "<< " value " >>")))

(deftype ErrorResult [error callback-modifier]
  clojure.lang.IDeref
  (deref [_]
    (if (instance? Throwable error)
      (throw error)
      (throw (Exception. (str error)))))
  Result
  (success [_ _]
    :lamina/already-realized!)
  (success! [_ _]
    :lamina/already-realized!)
  (error [_ _]
    :lamina/already-realized!)
  (error! [_ _]
    :lamina/already-realized!)
  (claim [_]
    false)
  (success-value [_ default-value]
    default-value)
  (error-value [_ _]
    error)
  (result [_]
    :error)
  (subscribe [_ callback]
    ((callback-modifier (.on-error ^ResultCallback callback)) error))
  (cancel-callback [_ callback]
    false)
  (toString [_]
    (str "<< ERROR: " error " >>")))

;;;

(deftype ResultState [mode value])

(defmacro compare-and-trigger! [old-mode new-mode lock state callbacks f value]
  `(io! "Cannot modify result-channels inside a transaction."
     (let [callbacks# ~callbacks
           value# ~value]
       (let [result# (l/with-exclusive-lock* ~lock
                       (case (.mode ~state)
                         ~old-mode (do (set! ~state (ResultState. ~new-mode value#)) true)
                         ~@(if (= ::none old-mode)
                             `(::claimed :lamina/already-claimed!)
                             `(::none :lamina/not-claimed!))
                         :lamina/already-realized!))]
         (if-not (= true result#)
           result#
           (let [result# (case (.size callbacks#)
                           0 :lamina/realized
                           1 ((~f ^ResultCallback (.get callbacks# 0)) value#)
                           (do
                             (doseq [^ResultCallback c# callbacks#]
                               ((~f c#) value#))
                             :lamina/split))]
             (.clear callbacks#)
             result#))))))

(deftype ResultChannel
  [^AsymmetricLock lock
   ^CopyOnWriteArrayList callbacks
   ^:volatile-mutable ^ResultState state
   success-callback-modifier
   error-callback-modifier]

  clojure.lang.IDeref
  (deref [this]
    (if-let [result (let [state state
                          value (.value state)]
                      (case (.mode state)
                        ::success value
                        ::error (if (instance? Throwable value)
                                  (throw value)
                                  (throw (Exception. (str value))))
                        nil))]
      result
      (let [^CountDownLatch latch (CountDownLatch. 1)
            f (fn [_] (.countDown latch))]
        (subscribe this (ResultCallback.
                          nil
                          (success-callback-modifier f)
                          (error-callback-modifier f)))
        (.await latch)
        (let [state state
              value (.value state)]
          (case (.mode state)
            ::success value
            ::error (if (instance? Throwable value)
                      (throw value)
                      (throw (Exception. (str value))))
            nil)))))
  
  Result
  (success [_ val]
    (compare-and-trigger! ::none ::success lock state callbacks .on-success val))

  (success! [_ val]
    (compare-and-trigger! ::claimed ::success lock state callbacks .on-success val))

  (error [_ err]
    (compare-and-trigger! ::none ::error lock state callbacks .on-error err))
  
  (error! [_ err]
    (compare-and-trigger! ::claimed ::error lock state callbacks .on-error err))

  (claim [_]
    (io! "Cannot modify result-channels inside a transaction."
      (l/with-exclusive-lock* lock
        (when (= ::none (.mode state))
          (set! state (ResultState. ::claimed nil))
          true))))

  ;;;
  
  (success-value [_ default-value]
    (let [state state]
      (if (= ::success (.mode state))
        (.value state)
        default-value)))
  
  (error-value [_ default-value]
    (let [state state]
      (if (= ::error (.mode state))
        (.value state)
        default-value)))
  
  (result [_]
    (case (.mode state)
      ::success :success
      ::error :error
      nil))

  ;;;

  (subscribe [_ callback]
    (io! "Cannot modify result-channels inside a transaction."
      (let [^ResultCallback callback callback
            x (l/with-lock lock
                (case (.mode state)
                  ::success (success-callback-modifier (.on-success callback))
                  ::error (error-callback-modifier (.on-error callback))
                  ::none (do
                           (.add callbacks
                             (ResultCallback.
                               callback
                               (success-callback-modifier (.on-success callback))
                               (error-callback-modifier (.on-error callback))))
                           :lamina/subscribed)))]
        (if-not (= :lamina/subscribed x)
          (x (.value state))
          x))))
  
  (cancel-callback [_ callback]
    (io! "Cannot modify result-channels inside a transaction."
      (l/with-lock lock
        (case (.mode state)
          ::error   false
          ::success false
          ::none    (.remove callbacks callback)))))
  
  (toString [_]
    (let [state state]
      (case (.mode state)
        ::error   (str "<< ERROR: " (.value state) " >>")
        ::success (str "<< " (.value state) " >>")
        ::none    "<< ... >>"))))

;;;

(defn success-result
  ([value]
     (success-result value identity))
  ([value callback-modifier]
     (SuccessResult. value callback-modifier)))

(defn error-result
  ([error]
     (error-result error identity))
  ([error callback-modifier]
     (ErrorResult. error callback-modifier)))

(defn result-channel
  ([]
     (result-channel identity identity))
  ([success-callback-modifier error-callback-modifier]
     (ResultChannel.
       (l/asymmetric-lock)
       (CopyOnWriteArrayList.)
       (ResultState. ::none nil)
       success-callback-modifier
       error-callback-modifier)))

(defn result-callback [on-success on-error]
  (ResultCallback. nil on-success on-error))

(defn result-channel? [x]
  (or
    (instance? ResultChannel x)
    (instance? SuccessResult x)
    (instance? ErrorResult x)))

;;;

(defmethod print-method SuccessResult [o ^Writer w]
  (.write w (str o)))

(defmethod print-method ErrorResult [o ^Writer w]
  (.write w (str o)))

(defmethod print-method ResultChannel [o ^Writer w]
  (.write w (str o)))
