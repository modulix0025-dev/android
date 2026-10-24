Raw dialect pairs from approved sources that CI cannot fetch itself (they need a personal access token),
in the format written by `tools/tm_sources.py` (`<source>/<dialect>.tsv`). The CI job `tm-build` builds
the shipped corpora from its own fetch plus this folder. See `docs/LOCAL_HF_SOURCES_PROMPT.md`.
