package io.touravatar.rag

/**
 * Retrieves relevant exhibit / venue knowledge snippets for a user query.
 *
 * For an MVP this is just an interface. The production implementation should:
 *   - Tokenize Chinese with jieba (or similar)
 *   - Index a SQLite-backed snippet table with BM25 scoring
 *   - Optionally fuse with a small embedding model (e.g. MiniLM-L6) via RRF
 *   - Cap retrieved context to ~600 chars to leave room for LLM
 */
interface RagRetriever {
    suspend fun retrieve(query: String, topK: Int = 3): List<RagSnippet>
}

data class RagSnippet(
    val id: String,
    val title: String,
    val text: String,
    val score: Double,
)
