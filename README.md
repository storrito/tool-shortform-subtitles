# Short-form subtitle workflow

Dockerized workflow for rendering animated `caption-clip-wipe` subtitles as transparent PNG frames.

## Requirements

- Docker
- Babashka (`bb`)

## Usage

```bash
./render transcript.edn
```

By convention, frames are written next to the transcript:

```text
frames/
  frame_000001.png
  frame_000002.png
  ...
```

The script builds the Docker image automatically and then renders inside Docker.

## Transcript format

Only EDN from `storrito/tool-speech-to-text` is supported:

```clojure
{:segments [{:t 0 :d 3200 :text "First sentence or segment."}]
 :words [{:t 0 :d 420 :text "First"}
         {:t 420 :d 380 :text "Storrito" :highlight? true}]}
```

`:t` and `:d` are milliseconds. Set `:highlight? true` on a word to render it in the highlight color.

## Output

The rendered frames are:

```text
1080x1920 transparent PNG, 30fps
```

Overlay them later with FFmpeg or another video tool.

## Optional WebM assembly

If you need a transparent WebM from the PNG frames:

```bash
./assemble-webm
```

By convention, this reads `frames/frame_%06d.png` and writes `subtitles.webm`.

This runs FFmpeg with VP9 alpha encoding.

## Customize

Edit `template/index.html` for caption position, size, color, and animation style.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
