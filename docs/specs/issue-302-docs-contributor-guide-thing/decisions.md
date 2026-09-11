## D1: Content organization — single section vs fragmented

**Choice:** Single new `### Knowledge Model Internals` section inside `## Internal Architecture`
**Alternatives:**
- Spread across multiple new sections by concern — fragments a tightly coupled subsystem, harder to navigate for someone extending the knowledge model
**Rationale:** The 7 topics are all part of one subsystem. Someone extending the knowledge model needs most of them in one sitting. Follows existing guide pattern (RAG topics adjacent, CBR topics adjacent).
**Trade-offs:** Less granular TOC — but the subsection headers within provide navigation.
**Sources:** contributor-guide.md existing structure, issue #302 topic list
**Exploration:** quick
**Status:** captured

## D2: Flyway scope — narrow pattern example

**Choice:** Document V4 (SubgraphType→string migration) as a reference pattern only
**Alternatives:**
- Broad Flyway guide covering version allocation, naming, cross-module coordination — drifts from the knowledge-model focus of the issue
**Rationale:** The issue's focus is the knowledge model. V4 is a concrete, well-bounded example. Platform builders can extrapolate.
**Trade-offs:** No general Flyway guidance — but that's a separate concern for a separate issue.
**Sources:** issue #302 body, V4 migration in mindmap-sqlite
**Exploration:** quick
**Status:** captured
