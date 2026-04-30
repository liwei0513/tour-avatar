package io.touravatar.rag

/**
 * Tiny in-memory keyword retriever. Intended as a placeholder so the LLM
 * pipeline can be exercised end-to-end before a real BM25/embedding index
 * is wired in.
 *
 * Operators of a real venue should replace this with a SQLite + jieba +
 * BM25 implementation seeded from their own catalog (CSV / JSON).
 */
class InMemoryRagRetriever(
    private val snippets: List<RagSnippet> = sampleSnippets(),
) : RagRetriever {

    override suspend fun retrieve(query: String, topK: Int): List<RagSnippet> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        val terms = q.split(Regex("[\\s，。、？！,.?!]+")).filter { it.isNotBlank() }
        return snippets
            .map { snippet ->
                val haystack = (snippet.title + " " + snippet.text).lowercase()
                val hits = terms.count { haystack.contains(it) }
                snippet.copy(score = hits.toDouble())
            }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(topK)
    }

    companion object {
        fun sampleSnippets(): List<RagSnippet> = listOf(
            RagSnippet(
                id = "demo-1",
                title = "示例展品 · 青铜编钟",
                text = "青铜编钟为中国古代礼乐器，多见于先秦贵族墓葬。曾侯乙编钟出土于湖北随州，共 65 件，跨度五个八度。",
                score = 0.0,
            ),
            RagSnippet(
                id = "demo-2",
                title = "示例展品 · 汝窑天青釉",
                text = "汝窑为北宋五大名窑之一，烧造于河南宝丰县清凉寺。釉色如「雨过天青云破处」，传世品稀少。",
                score = 0.0,
            ),
            RagSnippet(
                id = "demo-3",
                title = "示例景点 · 天坛祈年殿",
                text = "祈年殿位于北京天坛，建于明永乐十八年（1420），三重檐圆形大殿，全木结构无钉无铆。",
                score = 0.0,
            ),
        )
    }
}
