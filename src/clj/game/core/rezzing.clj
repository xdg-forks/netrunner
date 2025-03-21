(ns game.core.rezzing
  (:require
    [game.core.card :refer [asset? condition-counter? get-card ice? rezzed? upgrade?]]
    [game.core.card-defs :refer [card-def]]
    [game.core.cost-fns :refer [rez-additional-cost-bonus rez-cost]]
    [game.core.effects :refer [is-disabled? unregister-static-abilities update-disabled-cards]]
    [game.core.eid :refer [complete-with-result effect-completed make-eid]]
    [game.core.engine :refer [register-pending-event queue-event checkpoint pay register-events resolve-ability trigger-event unregister-events]]
    [game.core.flags :refer [can-host? can-rez?]]
    [game.core.ice :refer [update-ice-strength]]
    [game.core.initializing :refer [card-init deactivate]]
    [game.core.moving :refer [trash-cards]]
    [game.core.payment :refer [build-spend-msg can-pay? merge-costs ->c]]
    [game.core.runs :refer [continue]]
    [game.core.say :refer [play-sfx system-msg implementation-msg]]
    [game.core.toasts :refer [toast]]
    [game.core.to-string :refer [card-str]]
    [game.core.update :refer [update!]]
    [game.macros :refer [continue-ability effect wait-for]]
    [game.utils :refer [enumerate-str to-keyword]]))

(defn get-rez-cost
  [state side card {:keys [ignore-cost alternative-cost cost-bonus]}]
  (merge-costs
    (cond
      (= :all-costs ignore-cost) [(->c :credit 0)]
      alternative-cost (when-not (is-disabled? state side card) alternative-cost)
      :else (let [cost (rez-cost state side card {:cost-bonus cost-bonus})
                  additional-costs (rez-additional-cost-bonus state side card (when ignore-cost #(not= :credit (:cost/type %))))]
              (concat
                (when-not ignore-cost
                  [(->c :credit cost)])
                (when (not (:disabled card))
                  additional-costs))))))

(defn trash-hosted-cards
  [state side eid card]
  (let [hosted-cards (seq (remove condition-counter? (:hosted card)))]
    (if (can-host? state card)
      (effect-completed state side eid)
      (wait-for (trash-cards state side hosted-cards {:unpreventable true :game-trash true})
                (when (pos? (count hosted-cards))
                  (system-msg state side (str "trashes " (enumerate-str (map #(card-str state %) hosted-cards))
                                              " because " (:title card)
                                              " cannot host cards")))
                (effect-completed state side eid)))))

(defn- complete-rez
  [state side eid
   {:keys [disabled] :as card}
   {:keys [alternative-cost ignore-cost no-warning no-msg press-continue] :as args}]
  (let [cdef (card-def card)
        costs (get-rez-cost state side card args)]
    (wait-for (pay state side (make-eid state eid) card costs)
              (let [{:keys [msg cost-paid]} async-result]
                (if-not msg
                  (effect-completed state side eid)
                  (let [_ (when (:derezzed-events cdef)
                            (unregister-events state side card))
                        card (if disabled
                               (update! state side (assoc card :rezzed :this-turn))
                               (card-init state side (assoc card :rezzed :this-turn) {:resolve-effect false :init-data true}))]
                    (doseq [h (:hosted card)]
                      (update! state side (-> h
                                              (update-in [:zone] #(map to-keyword %))
                                              (update-in [:host :zone] #(map to-keyword %)))))
                    (when-not no-msg
                      (system-msg state side
                                  (str (build-spend-msg msg "rez" "rezzes")
                                       (:title card)
                                       (cond
                                         alternative-cost " by paying its alternative cost"
                                         ignore-cost " at no cost")))
                      (implementation-msg state card))
                    (when (and (not no-warning) (:corp-phase-12 @state))
                      (toast state :corp "You are not allowed to rez cards between Start of Turn and Mandatory Draw.
                                         Please rez prior to clicking Start Turn in the future." "warning"
                             {:time-out 0 :close-button true}))
                    (let [rez-byte (:rez-sound (card-def card))]
                      (if (ice? card)
                        (do (update-ice-strength state side card)
                            (when-not (:silent args) (play-sfx state side (or rez-byte "rez-ice"))))
                      (when-not (:silent args) (play-sfx state side (or rez-byte "rez-other")))))
                    (swap! state update-in [:stats :corp :cards :rezzed] (fnil inc 0))
                    (when-let [card-ability (:on-rez cdef)]
                      (register-pending-event state :rez card card-ability))
                    (queue-event state :rez {:card (get-card state card)
                                             :cost cost-paid})
                    (wait-for
                      (trash-hosted-cards state side (make-eid state eid) (get-card state card))
                      (wait-for
                        (checkpoint state nil (make-eid state eid) {:duration :rez})
                        (when press-continue
                          (continue state side nil))
                        (complete-with-result state side eid {:card (get-card state card)})))))))))

(defn can-pay-to-rez?
  ([state side eid card] (can-pay-to-rez? state side eid card nil))
  ([state side eid card args]
   (let [eid (assoc eid :source-type :rez)
         card (get-card state card)
         costs (or (get-rez-cost state side card args) 0)
         alternative-cost (when (and card
                                     (not (is-disabled? state side card)))
                            (:alternative-cost (card-def card)))]
     (or (and alternative-cost
              (can-pay? state side eid card nil alternative-cost))
         (can-pay? state side eid card nil costs)))))

(defn rez
  "Rez a corp card."
  ([state side eid card] (rez state side eid card nil))
  ([state side eid card
    {:keys [ignore-cost force declined-alternative-cost alternative-cost] :as args}]
   (let [eid (assoc eid :source-type :rez)
         card (get-card state card)
         alternative-cost (when (and card
                                     (not alternative-cost)
                                     (not (is-disabled? state side card))
                                     (not declined-alternative-cost))
                            (:alternative-cost (card-def card)))]
     (if (and card
              (or force
                  (can-rez? state side card))
              (or (asset? card)
                  (ice? card)
                  (upgrade? card)
                  (:install-rezzed (card-def card))))
       (if (and alternative-cost
                (not ignore-cost)
                (can-pay? state side eid card nil alternative-cost))
         (continue-ability
           state side
           {:optional
            {:prompt "Pay the alternative Rez cost?"
             :yes-ability {:async true
                           :effect (effect (rez eid card (merge args {:ignore-cost true
                                                                      :alternative-cost alternative-cost})))}
             :no-ability {:async true
                          :effect (effect (rez eid card (merge args {:declined-alternative-cost true})))}}}
           card nil)
         (complete-rez state side eid card args))
       (effect-completed state side eid)))))

(defn- derez-message
  ;; note:
  ;;  source-card - the card that's derezzing (optional)
  ;;  and-then - text to append to the end of the message (ie derezz x` and trash itself`)
  ;;              I suggest only using this if the rhs thing is part of the same instruction.
  ;;  include-cost-from-eid [eid] - include the last payment str from the eid as if it was for this
  [state side eid cards {:keys [and-then] :as msg-keys}]
  (let [card-strs (enumerate-str (map #(card-str state % {:visible true}) cards))
        prepend-cost-str (get-in msg-keys [:include-cost-from-eid :latest-payment-str])
        source-card (:source eid)
        title (or (:title source-card) (:printed-title source-card))]
    (system-msg
      state side
      (cond
        (not source-card) (str "derezzes " card-strs and-then)
        prepend-cost-str (str prepend-cost-str " to use " title " to derez " card-strs and-then)
        :else (str "uses " title " to derez " card-strs and-then)))))

(defn derez
  "Derez a number of corp cards."
  ([state side eid cards] (derez state side eid cards nil))
  ([state side eid cards {:keys [suppress-checkpoint no-event no-msg msg-keys] :as args}]
   (let [cards (if (sequential? cards)
                 (filterv #(and (get-card state %) (rezzed? %)) (flatten cards))
                 [cards])]
     (if-not (seq cards)
       (effect-completed state side eid)
       (do (doseq [c cards]
             (unregister-events state :corp c)
             (update! state :corp (deactivate state :corp c true))
             (let [cdef (card-def c)]
               (when-let [derez-effect (:derez-effect cdef)]
                 ;; this is currently only for lycian fixing subtypes on derez
                 ;; should happen even if the card is disabled - nbk
                 (resolve-ability state :corp derez-effect (get-card state c) cdef))
               (when-let [derezzed-events (:derezzed-events cdef)]
                 (register-events state :corp c (map #(assoc % :condition :derezzed) derezzed-events))))
             (unregister-static-abilities state :corp c))
           (update-disabled-cards state)
           (when-not no-event
             (queue-event state :derez {:cards cards
                                        :side side}))
           (update-disabled-cards state)
           (when-not no-msg
             (derez-message state side eid cards msg-keys))
           (if suppress-checkpoint
             (effect-completed state side eid)
             (checkpoint state side eid)))))))
