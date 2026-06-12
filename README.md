# Short-form subtitle workflow

Dockerized workflow for rendering animated subtitle templates as transparent PNG frames.

## Requirements

- Docker
- Babashka (`bb`)

## Usage

```bash
./render params.edn
```

By convention, frames are written next to the params file:

```text
frames/
  frame_000001.png
  frame_000002.png
  ...
```

The script builds the Docker image automatically and then renders inside Docker.

## Params format

The params file is an EDN map. The transcript fields stay at the top level and use the existing `storrito/tool-speech-to-text` shape:

```clojure
{:template :caption-clip-wipe
 :segments [{:t 0 :d 3200 :text "First sentence or segment."}]
 :words [{:t 0 :d 420 :text "First"}
         {:t 420 :d 380 :text "Storrito" :highlight? true}]}
```

`:t` and `:d` are milliseconds. Set `:highlight? true` on a word to render it in the highlight color for templates that support highlighting.

If `:template` is omitted, `:caption-clip-wipe` is used.

## Templates

### `:caption-clip-wipe`

The original vertical subtitle overlay:

```clojure
{:template :caption-clip-wipe
 :segments [...]
 :words [...]}
```

Output is `1080x1920` transparent PNG frames at 30fps.

### `:caption-emoji-pop`

The HyperFrames `caption-emoji-pop` template:

```clojure
{:template :caption-emoji-pop
 :segments [...]
 :words [...]
 :word-emoji {"tiktok" "🎵"
              "schedule" "📅"}}
```

`:word-emoji` is merged with the template's built-in defaults. `:word_emoji` and `:wordEmoji` are accepted as aliases. The template keeps its upstream defaults for entries not provided by params.

Output follows the template's own composition size, currently `1920x1080` transparent PNG frames at 30fps.

## Output

The rendered frames are transparent PNG files. Overlay them later with FFmpeg or another video tool.

## Optional WebM assembly

If you need a transparent WebM from the PNG frames:

```bash
./assemble-webm
```

By convention, this reads `frames/frame_%06d.png` and writes `subtitles.webm`.

This runs FFmpeg with VP9 alpha encoding.

## Customize

Edit templates in `templates/<template-name>/index.html`.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
