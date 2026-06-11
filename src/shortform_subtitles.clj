(ns shortform-subtitles
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def max-words-per-group 4)
(def pause-break-seconds 0.28)

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

(defn parse-transcript [transcript-path]
  (edn/read-string (slurp transcript-path)))

(defn transcript-words [transcript-path]
  (let [data (parse-transcript transcript-path)
        words (if (map? data)
                (edn-transcript-words data)
                [])]
    (when (empty? words)
      (die! (str "No EDN timed words found in transcript: " transcript-path)))
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
  (->> words (map :end) (apply max) (+ 0.2) (max 1.0)))

(defn caption-data [transcript-path]
  (let [words (transcript-words transcript-path)]
    {:duration (double (caption-duration words))
     :words words
     :rawGroups (group-word-indexes words)}))

(defn caption-data-js [data]
  (str "window.__captionClipWipeData = "
       (json/generate-string data {:pretty true})
       ";\n"))

(defn patch-index-duration! [project-dir duration]
  (let [index-path (fs/path project-dir "index.html")]
    (spit (str index-path)
          (str/replace (slurp (str index-path))
                       #"data-duration=\"[^\"]+\""
                       (str "data-duration=\"" duration "\"")))))

(defn prepare-project! [project-dir transcript-path]
  (let [data (caption-data transcript-path)]
    (fs/delete-tree project-dir)
    (fs/create-dirs project-dir)
    (sh! "cp" "-R" (str (fs/path (app-root) "template") "/.") (str project-dir))
    (spit (str (fs/path project-dir "caption-data.js")) (caption-data-js data))
    (patch-index-duration! project-dir (:duration data))))

(defn chown-output! [output-path]
  (let [uid (System/getenv "HOST_UID")
        gid (System/getenv "HOST_GID")]
    (when (and (seq uid) (seq gid))
      (try
        (sh! "chown" "-R" (str uid ":" gid) output-path)
        (catch Exception e
          (binding [*out* *err*]
            (println "Warning: could not chown output:" (.getMessage e))))))))

(defn render! [{:keys [transcript frames-dir]}]
  (when-not (and transcript frames-dir)
    (die! "Container usage: --transcript transcript.edn --frames-dir frames"))
  (let [project-dir (fs/path "/tmp/shortform-subtitles/project")]
    (prepare-project! project-dir transcript)
    (fs/delete-tree frames-dir)
    (fs/create-dirs frames-dir)
    (sh-dir! project-dir "hf-render" "--format" "png-sequence" "--output" frames-dir)
    (chown-output! frames-dir)
    (println "Wrote" frames-dir)))

(defn -main [& argv]
  (try
    (render! (:opts (cli/parse-args argv {:spec {:transcript {:coerce :string}
                                                :frames-dir {:coerce :string}}})))
    (catch Exception e
      (binding [*out* *err*]
        (println "Error:" (.getMessage e)))
      (System/exit 1))))
