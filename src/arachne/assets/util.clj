(ns arachne.assets.util
  (:require [clojure.core.async :as a :refer [<!]]))

(defn dist
  "Create a mult of the supplied channel. The mult works identically to
  the built-in mult, except it always retains the most recent value from the
  input channel and will provide it immediately as an initial value to new
  taps."
  [ch]
  (let [buffer (atom nil)
        taps (atom {})
        m (reify
              a/Mux
              (muxch* [_] ch)

              a/Mult
              (tap* [_ ch close?]
                (swap! taps assoc ch close?)
                (when-let [v @buffer]
                  (a/put! ch v))
                nil)
              (untap* [_ ch] (swap! taps dissoc ch) nil)
              (untap-all* [_] (reset! taps {}) nil))
        dchan (a/chan 1)
        dctr (atom nil)
        done (fn [_] (when (zero? (swap! dctr dec))
                       (a/put! dchan true)))]
    (a/go-loop []
      (let [val (<! ch)]
        (if (nil? val)
          (doseq [[c close?] @taps] (when close? (a/close! c)))
          (let [chs (keys @taps)]
            (reset! buffer val)
            (reset! dctr (count chs))
            (doseq [c chs]
              (when-not (a/put! c val done)
                (done nil)
                (a/untap* m c)))
            ;; wait for all
            (when (seq chs)
              (<! dchan))
            (recur)))))
    m))