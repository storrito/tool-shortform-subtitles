(ns shortform-subtitles
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def default-template "caption-clip-wipe")
(def max-words-per-group 4)
(def pause-break-seconds 0.28)
(def caption-tail-seconds 0.8)

(defn app-root []
  (or (System/getenv "APP_DIR") "/app"))

(defn sh!
  [& args]
  (println "+" (str/join " " (map str args)))
  (let [{:keys [exit]} (apply p/shell {:continue true} (map str args))]
    (when-not (zero? exit)
      (throw (ex-info "Command failed" {:exit exit :command args})))))

(defn sh-dir! [dir & args]
  (println "+" (str/join " " (map str args)))
  (let [{:keys [exit]} (apply p/shell {:dir (str dir) :continue true} (map str args))]
    (when-not (zero? exit)
      (throw (ex-info "Command failed" {:exit exit :command args :dir dir})))))

(defn die! [message]
  (binding [*out* *err*]
    (println message))
  (System/exit 1))

(defn value-name [value]
  (cond
    (nil? value) nil
    (or (keyword? value) (symbol? value)) (name value)
    (string? value) value
    :else (str value)))

(defn template-name [params]
  (let [template (or (some-> (:template params) value-name)
                     (some-> (get params "template") value-name)
                     default-template)]
    (when-not (re-matches #"[a-z0-9][a-z0-9-]*" template)
      (die! (str "Invalid template name: " template)))
    template))

(defn sentence-end? [text]
  (boolean (re-find #"[.!?]$" (str text))))

(defn correction [text]
  (if (re-matches #"(?i)reto\.com\.?" text)
    "Storrito."
    text))

(defn edn-transcript-words [data]
  (->> (:words data)
       (keep (fn [word]
               (let [text (some-> (:text word) str str/trim)
                     start-ms (:t word)
                     duration-ms (:d word)]
                 (when (and (seq text) (number? start-ms) (number? duration-ms))
                   (let [start (/ start-ms 1000.0)
                         end (/ (+ start-ms duration-ms) 1000.0)]
                     {:text (correction text)
                      :start (double start)
                      :end (double (if (<= end start) (+ start 0.2) end))
                      :highlight? (true? (:highlight? word))})))))
       vec))

(defn parse-params [params-path]
  (let [data (edn/read-string (slurp params-path))]
    (when-not (map? data)
      (die! (str "Params EDN must contain a map: " params-path)))
    data))

(defn params-words [params params-path]
  (let [words (edn-transcript-words params)]
    (when (empty? words)
      (die! (str "No EDN timed words found in params: " params-path)))
    words))

(defn group-word-indexes [words]
  (loop [idxs (range (count words))
         current []
         groups []]
    (if-let [idx (first idxs)]
      (let [word (nth words idx)]
        (if (empty? current)
          (recur (rest idxs) [idx] groups)
          (let [prev (nth words (peek current))
                gap (- (:start word) (:end prev))
                should-break? (or (>= gap pause-break-seconds)
                                  (>= (count current) max-words-per-group)
                                  (sentence-end? (:text prev)))]
            (if should-break?
              (recur (rest idxs) [idx] (conj groups [(first current) (peek current)]))
              (recur (rest idxs) (conj current idx) groups)))))
      (cond-> groups
        (seq current) (conj [(first current) (peek current)])))))

(defn caption-duration [words]
  (->> words (map :end) (apply max) (+ caption-tail-seconds) (max 1.0)))

(defn parse-duration [value]
  (cond
    (number? value) (double value)
    (string? value) (try
                      (Double/parseDouble value)
                      (catch Exception _ nil))
    :else nil))

(defn clean-word [text]
  (-> (str text)
      str/lower-case
      (str/replace #"[^a-z]" "")))

(defn word-emoji-param [params]
  (or (:word-emoji params)
      (:word_emoji params)
      (:wordEmoji params)
      (get params "word-emoji")
      (get params "word_emoji")
      (get params "wordEmoji")))

(defn normalize-word-emoji [word-emoji]
  (if (map? word-emoji)
    (->> word-emoji
         (keep (fn [[word emoji]]
                 (let [clean (clean-word (value-name word))]
                   (when (seq clean)
                     [clean (str emoji)]))))
         (into {}))
    {}))

(defn json-ready [value]
  (cond
    (keyword? value) (name value)
    (symbol? value) (name value)
    (map? value) (->> value
                      (map (fn [[k v]]
                             [(or (value-name k) "") (json-ready v)]))
                      (into {}))
    (sequential? value) (mapv json-ready value)
    :else value))

(defn params-data [params params-path]
  (let [words (params-words params params-path)
        duration (or (parse-duration (:duration params))
                     (parse-duration (get params "duration"))
                     (caption-duration words))]
    (-> (json-ready params)
        (assoc "template" (template-name params)
               "duration" duration
               "words" (json-ready words)
               "rawGroups" (group-word-indexes words)
               "wordEmoji" (normalize-word-emoji (word-emoji-param params)))
        (dissoc "word-emoji" "word_emoji"))))

(defn params-data-js [data]
  (str "window.__hyperframesParams = "
       (json/generate-string data {:pretty true})
       ";\n"))

(defn patch-index-duration! [project-dir duration]
  (let [index-path (fs/path project-dir "index.html")]
    (spit (str index-path)
          (str/replace (slurp (str index-path))
                       #"data-duration=\"[^\"]+\""
                       (str "data-duration=\"" duration "\"")))))

(defn prepare-project! [project-dir params-path]
  (let [params (parse-params params-path)
        data (params-data params params-path)
        template-dir (fs/path (app-root) "templates" (get data "template"))]
    (when-not (fs/directory? template-dir)
      (die! (str "Unknown template: " (get data "template"))))
    (when (fs/exists? project-dir)
      (fs/delete-tree project-dir))
    (fs/create-dirs project-dir)
    (sh! "cp" "-R" (str template-dir "/.") (str project-dir))
    (spit (str (fs/path project-dir "params-data.js")) (params-data-js data))
    (patch-index-duration! project-dir (get data "duration"))))

(defn chown-output! [output-path]
  (let [uid (System/getenv "HOST_UID")
        gid (System/getenv "HOST_GID")]
    (when (and (seq uid) (seq gid))
      (try
        (sh! "chown" "-R" (str uid ":" gid) output-path)
        (catch Exception e
          (binding [*out* *err*]
            (println "Warning: could not chown output:" (.getMessage e))))))))

(defn render! [{:keys [params transcript frames-dir]}]
  (let [params (or params transcript)]
    (when-not (and params frames-dir)
      (die! "Container usage: --params params.edn --frames-dir frames"))
    (let [project-dir (fs/path "/tmp/shortform-subtitles/project")]
      (prepare-project! project-dir params)
      (when (fs/exists? frames-dir)
        (fs/delete-tree frames-dir))
      (fs/create-dirs frames-dir)
      (sh-dir! project-dir "hf-render" "--format" "png-sequence" "--output" frames-dir)
      (chown-output! frames-dir)
      (println "Wrote" frames-dir))))

(defn -main [& argv]
  (try
    (render! (:opts (cli/parse-args argv {:spec {:params {:coerce :string}
                                                :transcript {:coerce :string}
                                                :frames-dir {:coerce :string}}})))
    (catch Exception e
      (binding [*out* *err*]
        (println "Error:" (.getMessage e)))
      (System/exit 1))))
