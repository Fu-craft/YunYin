package com.yunyin.music.data.lxsource

/**
 * Decides whether an in-flight lookup should be discarded because the player moved to another track.
 *
 * The rule is deliberately narrow, and it is a **pure function** because getting it wrong is what produced a bare
 * "Source error" on the device: `onMediaItemTransition` can arrive *after* the new item's data source has started
 * resolving, so cancelling on every transition killed the lookup for the track that was about to play. A lookup for
 * the same id as the new current item must therefore be left alone; only a lookup for a **different** id is
 * superseded.
 *
 * `currentTrackId == null` means the transition carried no id (e.g. the queue was cleared), in which case any
 * in-flight lookup is stale.
 */
internal object LxSupersedeRules {

    fun shouldSupersede(activeKey: Long?, currentTrackId: Long?): Boolean {
        if (activeKey == null) return false
        if (currentTrackId == null) return true
        return activeKey != currentTrackId
    }
}
