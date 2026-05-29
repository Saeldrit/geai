package com.github.saeldrit.geai.bundle

import com.github.saeldrit.geai.settings.GeaiSettings
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Selects the active [Ranker]. The deterministic ranker is always available; a future
 * `VectorRanker` registers here and is chosen when `graceVectorRanker` is on and embeddings exist.
 * Until then the flag degrades cleanly to deterministic, keeping the seam live without a half-built
 * embedding backend.
 */
object Rankers {

    fun active(): Ranker {
        if (GeaiSettings.getInstance().state.graceVectorRanker) {
            thisLogger().info("Geai GRACE: vector ranker requested but not yet available — using deterministic.")
        }
        return DeterministicRanker
    }
}
