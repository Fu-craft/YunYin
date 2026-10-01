package com.yunyin.music.data.together

import java.security.SecureRandom

/**
 * Room codes and the topic name they map to.
 *
 * Split out because this is the part of the public-service transport that is pure logic, and the part
 * a mistake would be hardest to notice: a code containing `O` next to `0`, or a topic that two
 * different codes collide onto, both look fine in code review and fail only in the hands of two real
 * people reading digits out loud to each other.
 */
internal object TogetherCode {

    /**
     * Deliberately missing `0`/`O` and `1`/`I`/`L`. The code is spoken aloud and typed by hand, and
     * those are the pairs that get entered wrong; the alphabet is still 31 characters, which is plenty.
     */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Twelve characters: not guessable from outside, still groupable in fours for reading out. */
    const val LENGTH = 12

    /**
     * Namespaced so these topics cannot collide with anything else on a shared public instance, and
     * versioned so a future protocol change can run alongside this one instead of against it.
     */
    const val TOPIC_PREFIX = "yunyin-together-v1-"

    fun fresh(random: SecureRandom = SecureRandom()): String =
        buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /**
     * The conversation identifier for a code.
     *
     * Normalised the same way the input field normalises it, so a code pasted in lower case or with a
     * dash typed into it lands on the topic the other side is publishing to.
     */
    fun topic(code: String): String =
        TOPIC_PREFIX + code.filter { it.isLetterOrDigit() }.uppercase()

    /** True when [code] has enough substance to be a room code for a transport expecting [length]. */
    fun isComplete(code: String, length: Int = LENGTH): Boolean =
        code.count { it.isLetterOrDigit() } >= length

    /**
     * Normalises typed input for a transport expecting [length] characters.
     *
     * Separators are dropped rather than rejected: a code is meant to be read aloud in groups, so people
     * type dashes and spaces into it.
     */
    fun normalise(input: String, length: Int = LENGTH): String =
        input.filter { it.isLetterOrDigit() }.uppercase().take(length)
}
