(ns lt.plugins.elm-light.docpreview
  (:require [lt.plugins.elm-light.clients :refer [try-connect]]
            [lt.plugins.elm-light.utils :refer [project-path]]
            [lt.object :as object]
            [lt.objs.eval :as eval]
            [lt.objs.clients :as clients]
            [lt.objs.command :as cmd]
            [lt.objs.editor.pool :as pool]
            [lt.objs.editor :as editor]
            [lt.objs.notifos :as notifos]
            [lt.objs.tabs :as tabs]
            [lt.util.dom :as dom]
            [lt.util.js :as js-util])
  (:require-macros [lt.macros :refer [defui behavior]]))




(defui preview-skeleton [this]
  [:div {:class "elm-doc-preview"}
   [:h1 "Generating elm docs, please wait a little..."]])



(object/object* ::elmdoc
                :tags [:elmdoc]
                :name "elmdoc"
                :behaviors [::on-close-destroy]
                :init (fn [this filename]
                        (object/update! this [:name] (constantly (str filename " - Live")))
                        (preview-skeleton this)))



(behavior ::on-close-destroy
          :triggers #{:close}
          :reaction (fn [this]
                      (when-let [ts (:lt.objs.tabs/tabset @this)]
                        (when (= (count (:objs @ts)) 1)
                          (tabs/rem-tabset ts)))
                      (when-let [ed (:owner-ed @this)]
                        (object/assoc-in! ed [:elmdoc] nil))
                      (object/destroy! this)))


;; (doseq [x (object/by-tag :elmdoc)]
;;   (println "Found one")
;;   (object/destroy! x))


(behavior ::gendoc
          :description "Preview package doc for a given Elm file"
          :triggers #{:gendoc}
          :reaction (fn [ed]
                      (let [info (:info @ed)
                            cl (eval/get-client! {:command :editor.elm.gendoc
                                                  :origin ed
                                                  :info info
                                                  :create try-connect})]
                        (notifos/working (str "Starting elm doc generation for: " (:path info)))
                        (clients/send cl
                                      :editor.elm.gendoc (assoc info :project-path (project-path (:path info)))
                                      :only ed))))



(defn- maybe-create-elm-preview [elmdoc]
  (if-let [preview (:preview @elmdoc)]
    preview
    (let [el (object/->content elmdoc)
          preview (.embed js/Elm js/Elm.DocPreview el (clj->js {:modules "[]" :selectModule ""}))]
      (object/assoc-in! elmdoc [:preview] preview)
      preview)))


(behavior ::elm-gendoc-res
          :triggers #{:elm.gendoc.res}
          :reaction (fn [ed res]
                      (notifos/done-working "Elm doc generated")
                      (when-let [elmdoc (:elmdoc @ed)]
                        (let [preview (maybe-create-elm-preview elmdoc)]
                          (.send (.-modules (.-ports preview)) res)
                          ;; Hack, when kittens go to die (:
                          (js-util/wait 50
                                        #(doseq [block (dom/$$ "pre code" (object/->content elmdoc))]
                                           (.highlightBlock js/hljs block)))))))


(behavior ::gen-on-save
          :triggers #{:save}
          :reaction (fn [ed]
                      (object/raise ed :gendoc)))


(behavior ::elm-docpreview
          :triggers #{:elm.docpreview}
          :reaction (fn [ed]
                      (let [path (-> @ed :info :path)
                            elmdoc-obj (or (:elmdoc @ed) (object/create ::elmdoc path))]
                        (tabs/add-or-focus! elmdoc-obj)
                        (object/assoc-in! ed [:elmdoc] elmdoc-obj)
                        (object/assoc-in! elmdoc-obj [:owner-ed] ed)
                        (object/add-behavior! ed ::gen-on-save)
                        (object/raise ed :gendoc))))



(cmd/command {:command :elm.docpreview
              :desc "Elm: Preview doc for selected file"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :elm.docpreview)))})
