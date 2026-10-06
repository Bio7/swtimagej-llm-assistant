# SWTImageJ LLM Assistant – Maven project

SWTImageJ plugin that connects SWTImageJ to OpenAI-compatible language models
(OpenAI, LM Studio, Ollama, …): chat window, SWTImageJ tools (images, editor,
Script Explorer, macros, Java), error markers with quick fixes, image
snapshots for vision models, reference documents (hybrid search), and optional
direct source browsing/search of selected reference folders (e.g. an
ImageJ/SWTImageJ source checkout).

* **Build and Eclipse import:** see [BUILDING.md](BUILDING.md)
* **Using the plugin:** see [docs/PLUGIN_README.md](docs/PLUGIN_README.md)

Quick start: copy the SWTImageJ bundle jar to `lib/org.eclipse.swt.imagej.jar`,
then `mvn clean package` → `target/LLM_Assistant.jar` → SWTImageJ `plugins` folder.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
Every source file carries the Apache 2.0 header; the license text is also
packaged into the plugin jar (`META-INF/LICENSE`).
