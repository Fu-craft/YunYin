package com.yunyin.music.data.net

import com.yunyin.music.core.Track

/** Where 心动模式's seed song came from. Logged (`stage=beat-mode seed=…`) and unit-tested. */
enum class BeatSeedPath(val label: String) {
    /** The song currently loaded in the player. */
    Current("current"),

    /** The first displayable track of the merged (local + cloud) liked list. */
    Liked("liked"),

    /** The first displayable track of `/recommend/songs` (daily recommendations). */
    Recommend("recommend"),
}

/** The chosen seed and its provenance. */
data class BeatSeed(val track: Track, val path: BeatSeedPath)

/**
 * Seed-selection rule for 心动模式 when the user taps it with nothing playing.
 *
 * NetEase's 心动模式 does not require a current song; it picks a seed and starts playback. The app follows
 * the same priority, in this order:
 *
 *  1. the **current track** (the pre-existing behaviour — it is what "more of this song" means);
 *  2. the **liked list**, local + cloud merged by [LikedSongsRepository] (the app already keeps it, so this
 *     needs no extra request when it has likes);
 *  3. **daily recommendations** (`/recommend/songs`, verified live on the deployed instance);
 *  4. otherwise there is no seed — the host reports `没有可用的歌曲，无法开启心动模式` rather than doing a
 *     silent no-op or installing a blank queue.
 *
 * Rows that fail the beat-mode display invariant ([BeatModeRules.isDisplayable]: a title and at least one
 * artist) are never chosen as a seed, so the queue cannot start on an id-only row.
 *
 * Pure and unit-tested with fakes ([BeatModeSeedRulesTest]); the host only supplies the lists.
 */
object BeatModeSeedRules {

    fun choose(
        current: Track?,
        liked: List<Track>,
        recommended: List<Track>,
    ): BeatSeed? {
        current?.takeIf(BeatModeRules::isDisplayable)?.let { return BeatSeed(it, BeatSeedPath.Current) }
        liked.firstOrNull(BeatModeRules::isDisplayable)?.let { return BeatSeed(it, BeatSeedPath.Liked) }
        recommended.firstOrNull(BeatModeRules::isDisplayable)?.let {
            return BeatSeed(it, BeatSeedPath.Recommend)
        }
        return null
    }
}
