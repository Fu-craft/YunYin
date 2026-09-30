package com.yunyin.music.data

import com.yunyin.music.core.NetResult
import com.yunyin.music.core.Track

/**
 * The liked songs, as one state shared by everything that shows them.
 *
 * ## The problem this solves
 *
 * Liking is local and is never written back to NetEase, but the account's own likes still exist. That
 * gives three sources of truth — the local additions, the local removals, and the cloud list — and the
 * heart, the list and the count each have to derive the same answer from them:
 *
 * ```
 * liked(id) = id ∈ added  OR  (id ∉ removed  AND  id ∈ cloud)
 * ```
 *
 * Keeping that rule in one place is the point. It was previously split: the heart read only the local
 * additions (so a song liked on NetEase showed an empty heart), the list merged the two (so it showed the
 * song anyway), and the count was briefly computed from the additions alone (so it read "1 首" beside a
 * 564-song list). One rule, one place, three views that cannot disagree.
 *
 * ## Not writing back
 *
 * Un-liking a cloud-liked song therefore only records a local removal. The song stays liked in NetEase and
 * will come back if this app's data is cleared — the honest consequence of not writing back, and preferable
 * to a heart that flips on again by itself.
 *
 * The cloud half is cached per account so the rule can be evaluated synchronously (the heart needs it during
 * composition), and `null` is tracked distinctly from the empty set so "not fetched yet" is never mistaken
 * for "nothing is liked in the cloud".
 */
class LikedSongsRepository(
    private val store: LikedSongsStore,
    private val music: MusicRepository,
) {

    private var cloudTracksCache: List<Track>? = null
    private var cloudUid: Long? = null

    /** Tracks liked in this app, and the ids explicitly un-liked locally. */
    fun added(): List<Track> = store.load()

    /**
     * Artwork for the liked list's own cover: the most recently **locally added** song, or null.
     *
     * The cover otherwise comes from the account's cloud playlist, which by construction cannot reflect
     * anything liked in this app — so a song added here never became the cover, which is the reported bug.
     *
     * Only the local half can be ordered. NetEase's `/likelist` returns ids with no timestamps, so there is
     * no way to compare a cloud like's time against a local one; the local additions are treated as the
     * newest, which is exactly true for the case being fixed (the user just tapped the heart in this app)
     * and is the only ordering the available data supports. Null means "nothing newer locally", and the
     * caller keeps the cloud cover.
     */
    fun newestAddedCoverUrl(): String? = newestCoverUrl(store.load())

    /**
     * The effective liked state of [id], from whatever is known right now.
     *
     * Synchronous so it can be read while composing a heart. Before the cloud half has been fetched a
     * cloud-only like reads as `false`; [ensureCloud] is what makes it settle, and the caller re-checks
     * once it finishes.
     */
    fun isLiked(id: Long): Boolean = isLiked(
        addedIds = store.load().mapTo(HashSet()) { it.id },
        removedIds = store.removedIds(),
        cloudIds = cloudTracksCache?.mapTo(HashSet()) { it.id }.orEmpty(),
        id = id,
    )

    /**
     * Flips the liked state of [track].
     *
     * A single entry point rather than add/remove at the call site, because the correct operation depends on
     * the *effective* state: un-liking a cloud-only song must record a removal (there is nothing in the
     * local list to delete), and liking a previously removed song must clear that removal.
     */
    fun toggle(track: Track) {
        if (track.id == 0L) return
        if (isLiked(track.id)) store.remove(track.id) else store.add(track)
    }

    /**
     * Fetches the account's cloud likes once per account, and returns whether they are now known.
     *
     * Returns false when the request failed, which the caller must not treat as "no cloud likes": doing so
     * is how an un-liked song reappears and how a count understates.
     */
    suspend fun ensureCloud(uid: Long): Boolean {
        if (uid == 0L) {
            cloudTracksCache = emptyList()
            cloudUid = uid
            return true
        }
        if (cloudUid == uid && cloudTracksCache != null) return true
        return when (val result = music.cloudLikedTracks(uid)) {
            is NetResult.Ok -> {
                cloudTracksCache = result.value
                cloudUid = uid
                // A different account's likes replaced the cache, so nothing keyed on the old set may
                // survive — see [forgetCloud].
                true
            }
            is NetResult.Err -> false
        }
    }

    /** The liked list: local additions first, then cloud likes that are neither added nor removed locally. */
    suspend fun tracks(uid: Long): List<Track> {
        val cloud = cloudTracks(uid).orEmpty()
        return mergeLiked(store.load(), cloud, store.removedIds())
    }

    /**
     * The size of the list [tracks] would return, or null when the cloud half is not known.
     *
     * Null rather than a local-only number: showing a half-known figure as the total is the bug that made
     * one freshly liked song display as "1 首" beside a 564-song list.
     */
    suspend fun count(uid: Long): Int? {
        if (uid == 0L) return store.load().size
        val cloud = cloudTracks(uid) ?: return null
        return mergeLiked(store.load(), cloud, store.removedIds()).size
    }

    /**
     * The cloud half, or null when it could not be read.
     *
     * Null and empty are different answers: empty means "the account has no cloud likes", null means "not
     * known", and only the first of those may be counted as zero.
     */
    private suspend fun cloudTracks(uid: Long): List<Track>? {
        if (uid == 0L) return emptyList()
        if (cloudUid == uid) return cloudTracksCache
        return if (ensureCloud(uid)) cloudTracksCache else null
    }

    /**
     * Drops the cached cloud half.
     *
     * Called when the account changes, so one account's likes are never used to answer for another.
     */
    fun forgetCloud() {
        cloudTracksCache = null
        cloudUid = null
    }
}

/**
 * The cover the liked list should show, given its locally added songs newest-first.
 *
 * The newest entry that actually has artwork: a track liked from a source without a cover would otherwise
 * blank the cover out, which is worse than showing the next newest one. A pure function because "which
 * cover is newest" is the requirement being fixed, and it is easier to trust when it is testable.
 */
internal fun newestCoverUrl(added: List<Track>): String? =
    added.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl

/**
 * The shared liked rule: an id is liked when it was added locally, or when the cloud knows it and it was
 * not removed locally.
 *
 * A pure function so the three views' agreement is a property that can be tested, rather than three
 * implementations that happen to match until one is edited.
 */
internal fun isLiked(
    addedIds: Set<Long>,
    removedIds: Set<Long>,
    cloudIds: Set<Long>,
    id: Long,
): Boolean = when {
    id in addedIds -> true
    id in removedIds -> false
    else -> id in cloudIds
}

/**
 * The liked list: local additions first, then cloud likes that are not known locally in either way.
 *
 * Both the local additions and the local removals are excluded from the cloud half — the first because it
 * is already present, the second because its absence is the point.
 */
internal fun mergeLiked(
    added: List<Track>,
    cloud: List<Track>,
    removedIds: Set<Long>,
): List<Track> {
    val addedIds = added.mapTo(HashSet()) { it.id }
    return added + cloud.filterNot { it.id in addedIds || it.id in removedIds }
}

/**
 * The size of [mergeLiked]'s result, from ids rather than tracks.
 *
 * Kept beside [mergeLiked] because the two must agree: a count computed by a different route is how the
 * number beside the row drifted from the number of rows inside it.
 */
internal fun mergedLikedCount(
    added: List<Track>,
    cloudIds: Set<Long>,
    removedIds: Set<Long>,
): Int {
    val addedIds = added.mapTo(HashSet()) { it.id }
    return added.size + cloudIds.count { it !in addedIds && it !in removedIds }
}
