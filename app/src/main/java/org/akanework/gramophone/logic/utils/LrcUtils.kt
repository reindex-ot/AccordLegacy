package org.akanework.gramophone.logic.utils

import android.os.Parcelable
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlinx.parcelize.Parcelize
import java.io.File
import java.nio.charset.Charset
import kotlin.math.pow

object LrcUtils {

    private const val TAG = "LrcUtils"

    @Parcelize
    enum class SpeakerLabel(val isWalaoke: Boolean) : Parcelable {
        Male(true), // Walaoke
        Female(true), // Walaoke
        Duet(true), // Walaoke
        Background(false), // iTunes
        Voice1(false), // iTunes
        Voice2(false), // iTunes
        None(false)
    }

    @OptIn(UnstableApi::class)
    fun extractAndParseLyrics(metadata: Metadata, trim: Boolean): MutableList<MediaStoreUtils.Lyric>? {
        for (i in 0..< metadata.length()) {
            val meta = metadata.get(i)
            val data =
                if (meta is VorbisComment && meta.key == "LYRICS") // ogg / flac
                    meta.value
                else if (meta is BinaryFrame && (meta.id == "USLT" || meta.id == "SYLT")) // mp3 / other id3 based
                    UsltFrameDecoder.decode(ParsableByteArray(meta.data))
                else if (meta is TextInformationFrame && (meta.id == "USLT" || meta.id == "SYLT")) // m4a
                    meta.values.joinToString("\n")
                else null
            val lyrics = data?.let {
                try {
                    parseLrcString(it, trim)
                } catch (e: Exception) {
                    Log.e(TAG, Log.getStackTraceString(e))
                    null
                }
            }
            return lyrics ?: continue
        }
        return null
    }

    @OptIn(UnstableApi::class)
    fun loadAndParseLyricsFile(
        musicFile: File?,
        trim: Boolean
    ): MutableList<MediaStoreUtils.Lyric>? {
        val lrcFile = musicFile?.let { File(it.parentFile, it.nameWithoutExtension + ".lrc") }
        return loadLrcFile(lrcFile)?.let {
            try {
                parseLrcString(it, trim)
            } catch (e: Exception) {
                Log.e(TAG, Log.getStackTraceString(e))
                null
            }
        }
    }

    private fun loadLrcFile(lrcFile: File?): String? {
        return try {
            if (lrcFile?.exists() == true)
                lrcFile.readBytes().toString(Charset.defaultCharset())
            else null
        } catch (e: Exception) {
            Log.e(TAG, e.message ?: Log.getStackTraceString(e))
            null
        }
    }

    /*
     * Formats we have to consider in this method are:
     *  - Simple LRC files (ref Wikipedia) ex: [00:11.22] hello i am lyric
     *  - "compressed LRC" with >1 tag for repeating line ex: [00:11.22][00:15.33] hello i am lyric
     *  - Invalid LRC with all-zero tags [00:00.00] hello i am lyric
     *  - Lyrics that aren't synced and have no tags at all
     *  - Translations, type 1 (ex: pasting first japanese and then english lrc file into one file)
     *  - Translations, type 2 (ex: translated line directly under previous non-translated line)
     *  - The timestamps can variate in the following ways: [00:11] [00:11:22] [00:11.22] [00:11.222] [00:11:222]
     * In the future, we also want to support:
     *  - Extended LRC (ref Wikipedia) ex: [00:11.22] <00:11.22> hello <00:12.85> i am <00:13.23> lyric
     *  - Wakaloke gender extension (ref Wikipedia)
     *  - [offset:] tag in header (ref Wikipedia)
     * We completely ignore all ID3 tags from the header as MediaStore is our source of truth.
     */
    @VisibleForTesting
    fun parseLrcString(lrcContent: String, trim: Boolean): MutableList<MediaStoreUtils.Lyric> {
        val timeMarksRegex = "\\[(\\d{2}:\\d{2})([.:]\\d+)?]".toRegex()
        val wordTimeMarksRegex = "<(\\d{2}:\\d{2})([.:]\\d+)?>".toRegex()
        val labelRegex = "(v\\d+|bg):\\s?".toRegex()
        val bgRegex = "\\[bg: (.*?)]".toRegex()
        val list = mutableListOf<MediaStoreUtils.Lyric>()
        var foundNonNull = false
        var lyricsText: StringBuilder? = StringBuilder()
        var currentLabel = SpeakerLabel.None
        var currentTimeStamp = -1L
        // Add all lines found on LRC (probably will be unordered because of "compression" or translation type)
        lrcContent.lines().forEach { line ->
            currentLabel = parseSpeakerLabel(line.replace(timeMarksRegex, ""))
            timeMarksRegex.findAll(line).let { sequence ->
                if (sequence.count() == 0) {
                    return@let
                }
                val lyricLine = line.substring(sequence.last().range.last + 1)
                    .let { if (trim) it.trim() else it }
                    .replace(labelRegex, "")
                sequence.forEach { match ->
                    val timeString = match.groupValues[1] + match.groupValues[2]
                    currentTimeStamp = parseTime(timeString)

                    if (!foundNonNull && currentTimeStamp > 0) {
                        foundNonNull = true
                        lyricsText = null
                    }
                    lyricsText?.append(lyricLine + "\n")

                    if (wordTimeMarksRegex.containsMatchIn(lyricLine)) {
                        val wordMatches = wordTimeMarksRegex.findAll(lyricLine)
                        val words = lyricLine.split(wordTimeMarksRegex)
                        var lastWordTimestamp = currentTimeStamp
                        val wordTimestamps = words.mapIndexedNotNull { index, _ ->
                            wordMatches.elementAtOrNull(index)?.let { match ->
                                val wordTimestamp = parseTime(match.groupValues[1] + match.groupValues[2])
                                Triple(words.take(index + 1).sumOf { it.length }, lastWordTimestamp, wordTimestamp).also {
                                    lastWordTimestamp = wordTimestamp
                                }
                            }
                        }
                        list.add(
                            MediaStoreUtils.Lyric(
                                timeStamp = currentTimeStamp,
                                content = lyricLine.replace(wordTimeMarksRegex, ""),
                                wordTimestamps = wordTimestamps,
                                label = currentLabel
                            )
                        )
                    } else {
                        list.add(
                            MediaStoreUtils.Lyric(
                                timeStamp = currentTimeStamp,
                                content = lyricLine,
                                label = currentLabel
                            )
                        )
                    }
                }
            }

            bgRegex.findAll(line).let { result ->
                if (result.count() == 0) {
                    return@let
                }
                result.forEach { match ->
                    currentLabel = SpeakerLabel.Background
                    val lyricLine = match.value.substring(5, match.value.length - 1)
                    if (wordTimeMarksRegex.containsMatchIn(lyricLine)) {
                        val wordMatches = wordTimeMarksRegex.findAll(lyricLine)
                        val words = lyricLine.split(wordTimeMarksRegex)
                        var lastWordTimestamp = currentTimeStamp
                        val wordTimestamps = words.mapIndexedNotNull { index, _ ->
                            wordMatches.elementAtOrNull(index)?.let { match ->
                                val wordTimestamp = parseTime(match.groupValues[1] + match.groupValues[2])
                                Triple(words.take(index + 1).sumOf { it.length }, lastWordTimestamp, wordTimestamp).also {
                                    lastWordTimestamp = wordTimestamp
                                }
                            }
                        }
                        list.add(
                            MediaStoreUtils.Lyric(
                                timeStamp = currentTimeStamp + 1,
                                content = lyricLine.replace(wordTimeMarksRegex, ""),
                                wordTimestamps = wordTimestamps,
                                label = currentLabel
                            )
                        )
                    } else {
                        list.add(
                            MediaStoreUtils.Lyric(
                                timeStamp = currentTimeStamp,
                                content = lyricLine,
                                label = currentLabel
                            )
                        )
                    }
                }
            }
        }

        // Sort and mark as translations all found duplicated timestamps (usually one)
        list.sortBy { it.timeStamp }
        var previousTs = -1L
        var translationItems = intArrayOf()
        list.forEach {
            // Merge lyric and translation
            if (it.timeStamp == previousTs && it.label != SpeakerLabel.Background) {
                list[list.indexOf(it) - 1].translationContent = it.content
                translationItems += list.indexOf(it)
            }
            previousTs = it.timeStamp!!
        }
        // Remove translation items
        translationItems.reversed().forEach { list.removeAt(it) }

        list.takeWhile { it.content.isEmpty() }.forEach { _ -> list.removeAt(0) }
        var absolutePosition = 0
        list.forEachIndexed { index, it ->
            if (it.content.isNotEmpty() && it.label != SpeakerLabel.Background) {
                it.absolutePosition = absolutePosition
                absolutePosition ++
            } else {
                it.absolutePosition = list[index - 1].absolutePosition
            }
        }
        if (list.isEmpty() && lrcContent.isNotEmpty()) {
            list.add(MediaStoreUtils.Lyric(null, lrcContent))
        } else if (!foundNonNull) {
            list.clear()
            list.add(MediaStoreUtils.Lyric(null, lyricsText!!.toString()))
        }
        return list
    }

    private fun parseSpeakerLabel(lyricContent: String): SpeakerLabel {
        val lyricLabel = lyricContent.substring(0, lyricContent.length.coerceAtMost(4))
        return when {
            lyricLabel.startsWith("v1: ") -> SpeakerLabel.Voice1
            lyricLabel.startsWith("v2: ") -> SpeakerLabel.Voice2
            lyricLabel.startsWith("F: ") -> SpeakerLabel.Female
            lyricLabel.startsWith("M: ") -> SpeakerLabel.Male
            lyricLabel.startsWith("D: ") -> SpeakerLabel.Duet
            else -> SpeakerLabel.None
        }
    }

    private fun parseTime(timeString: String): Long {
        val timeRegex = "(\\d{2}):(\\d{2})[.:](\\d+)".toRegex()
        val matchResult = timeRegex.find(timeString)

        val minutes = matchResult?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val seconds = matchResult?.groupValues?.get(2)?.toLongOrNull() ?: 0
        val millisecondsString = matchResult?.groupValues?.get(3)
        // if one specifies micro/pico/nano/whatever seconds for some insane reason,
        // scrap the extra information
        val milliseconds = (millisecondsString?.substring(
            0, millisecondsString.length.coerceAtMost(3)
        )?.toLongOrNull() ?: 0) * 10f.pow(3 - (millisecondsString?.length ?: 0)).toLong()

        return minutes * 60000 + seconds * 1000 + milliseconds
    }
}

// Class heavily based on MIT-licensed https://github.com/yoheimuta/ExoPlayerMusic/blob/77cfb989b59f6906b1170c9b2d565f9b8447db41/app/src/main/java/com/github/yoheimuta/amplayer/playback/UsltFrameDecoder.kt
// See http://id3.org/id3v2.4.0-frames
@OptIn(UnstableApi::class)
private class UsltFrameDecoder {
    companion object {
        private const val ID3_TEXT_ENCODING_ISO_8859_1 = 0
        private const val ID3_TEXT_ENCODING_UTF_16 = 1
        private const val ID3_TEXT_ENCODING_UTF_16BE = 2
        private const val ID3_TEXT_ENCODING_UTF_8 = 3

        fun decode(id3Data: ParsableByteArray): String? {
            if (id3Data.limit() < 4) {
                // Frame is malformed.
                return null
            }

            val encoding = id3Data.readUnsignedByte()
            val charset = getCharsetName(encoding)

            val lang = ByteArray(3)
            id3Data.readBytes(lang, 0, 3) // language
            val rest = ByteArray(id3Data.limit() - 4)
            id3Data.readBytes(rest, 0, id3Data.limit() - 4)

            val descriptionEndIndex = indexOfEos(rest, 0, encoding)
            val textStartIndex = descriptionEndIndex + delimiterLength(encoding)
            val textEndIndex = indexOfEos(rest, textStartIndex, encoding)
            return decodeStringIfValid(rest, textStartIndex, textEndIndex, charset)
        }

        private fun getCharsetName(encodingByte: Int): Charset {
            val name = when (encodingByte) {
                ID3_TEXT_ENCODING_UTF_16 -> "UTF-16"
                ID3_TEXT_ENCODING_UTF_16BE -> "UTF-16BE"
                ID3_TEXT_ENCODING_UTF_8 -> "UTF-8"
                ID3_TEXT_ENCODING_ISO_8859_1 -> "ISO-8859-1"
                else -> "ISO-8859-1"
            }
            return Charset.forName(name)
        }

        private fun indexOfEos(data: ByteArray, fromIndex: Int, encoding: Int): Int {
            var terminationPos = indexOfZeroByte(data, fromIndex)

            // For single byte encoding charsets, we're done.
            if (encoding == ID3_TEXT_ENCODING_ISO_8859_1 || encoding == ID3_TEXT_ENCODING_UTF_8) {
                return terminationPos
            }

            // Otherwise ensure an even index and look for a second zero byte.
            while (terminationPos < data.size - 1) {
                if (terminationPos % 2 == 0 && data[terminationPos + 1] == 0.toByte()) {
                    return terminationPos
                }
                terminationPos = indexOfZeroByte(data, terminationPos + 1)
            }

            return data.size
        }

        private fun indexOfZeroByte(data: ByteArray, fromIndex: Int): Int {
            for (i in fromIndex until data.size) {
                if (data[i] == 0.toByte()) {
                    return i
                }
            }
            return data.size
        }

        private fun delimiterLength(encodingByte: Int): Int {
            return if (encodingByte == ID3_TEXT_ENCODING_ISO_8859_1 || encodingByte == ID3_TEXT_ENCODING_UTF_8)
                1
            else
                2
        }

        private fun decodeStringIfValid(
            data: ByteArray,
            from: Int,
            to: Int,
            charset: Charset
        ): String {
            return if (to <= from || to > data.size) {
                ""
            } else String(data, from, to - from, charset)
        }
    }
}