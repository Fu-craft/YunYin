// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

/**
 * Decision rules for syncing a like to the NetEase account.
 *
 * Local state remains the UI truth; the cloud write is best-effort. Only a **real** session can write; anonymous
 * (`/register/anonimous`) and logged-out sessions stay local-only, silently. Rapid toggles of one track coalesce
 * (latest state wins after a short debounce), so double taps never fire conflicting requests.
 */
object LikeSyncRules {

    const val DEBOUNCE_MS = 500L

    enum class Session { NONE, ANONYMOUS, REAL }

    fun sessionOf(cookie: String, userId: Long, anonymous: Boolean): Session = when {
        cookie.isBlank() || userId <= 0L -> Session.NONE
        anonymous -> Session.ANONYMOUS
        else -> Session.REAL
    }

    fun shouldSync(session: Session): Boolean = session == Session.REAL
}
