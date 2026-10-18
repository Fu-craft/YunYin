package com.yunyin.music.data

import com.yunyin.music.data.playlist.PlaylistOwnership
import com.yunyin.music.data.playlist.PlaylistWrites
import com.yunyin.music.core.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playlist write contract and ownership rule.
 *
 * The NeteaseCloudMusicApi write endpoints take form fields with easily-confused names (`pid` vs `id`, `op` for
 * add/del); these tests pin the payloads so a typo cannot reach a real account. Ownership is pinned because rendering
 * or performing an edit on someone else's playlist is both a 401 and a broken affordance.
 */
class PlaylistManagementTest {

    @Test
    fun `the create payload uses the documented form fields`() {
        val form = PlaylistWrites.createForm(" 我的歌单 ", "  简介  ", isPrivate = false)
        assertEquals("我的歌单", form["name"])
        assertEquals("简介", form["desc"])
        assertEquals("0", form["privacy"])
        assertEquals("10", PlaylistWrites.createForm("x", "", isPrivate = true)["privacy"])
    }

    @Test
    fun `the update payload identifies the playlist with id`() {
        val form = PlaylistWrites.updateForm(123L, "新名字", "新简介")
        assertEquals("123", form["id"])
        assertEquals("新名字", form["name"])
        assertEquals("新简介", form["desc"])
    }

    @Test
    fun `track payload uses pid and op and comma-separated ids`() {
        assertEquals(
            mapOf("op" to "add", "pid" to "42", "tracks" to "1,2,3"),
            PlaylistWrites.tracksForm(42L, "add", listOf(1L, 2L, 3L)),
        )
        assertEquals("del", PlaylistWrites.tracksForm(42L, "del", listOf(9L))["op"])
        assertEquals(mapOf("id" to "42"), PlaylistWrites.deleteForm(42L))
    }

    @Test
    fun `a blank or over-long name is refused with a specific message`() {
        assertEquals("歌单名称不能为空", PlaylistWrites.validateName("   "))
        assertTrue(PlaylistWrites.validateName("a".repeat(PlaylistWrites.MAX_NAME_LENGTH)) == null)
        assertTrue(PlaylistWrites.validateName("a".repeat(PlaylistWrites.MAX_NAME_LENGTH + 1))!!.contains("不能超过"))
        assertNull(PlaylistWrites.validateDescription("简介"))
    }

    @Test
    fun `only the logged-in creator owns a playlist`() {
        val mine = Playlist(id = 1L, name = "我的", coverUrl = null, creatorUserId = 100L)
        assertTrue(PlaylistOwnership.canEdit(mine, uid = 100L))
        assertFalse(PlaylistOwnership.canEdit(mine, uid = 101L))
        assertFalse(PlaylistOwnership.canEdit(mine, uid = 0L))
    }

    @Test
    fun `the liked list and unknown creators are never owned`() {
        val liked = Playlist(id = 2L, name = "我喜欢的音乐", coverUrl = null, isLikedSongs = true, creatorUserId = 100L)
        assertFalse(PlaylistOwnership.canEdit(liked, uid = 100L))
        val unknown = Playlist(id = 3L, name = "推荐", coverUrl = null, creatorUserId = 0L)
        assertFalse(PlaylistOwnership.canEdit(unknown, uid = 100L))
        val zeroId = Playlist(id = 0L, name = "liked", coverUrl = null, creatorUserId = 100L)
        assertFalse(PlaylistOwnership.canEdit(zeroId, uid = 100L))
    }

    @Test
    fun `write login messages distinguish a guest from an expired session`() {
        assertTrue(PlaylistOwnership.loginMessage(0L).contains("未登录"))
        assertTrue(PlaylistOwnership.loginMessage(7L).contains("过期"))
    }
}
