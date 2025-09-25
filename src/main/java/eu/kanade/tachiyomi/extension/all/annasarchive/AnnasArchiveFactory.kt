package eu.kanade.tachiyomi.extension.all.annasarchive

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class AnnasArchiveFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(AnnasArchiveSource())
}