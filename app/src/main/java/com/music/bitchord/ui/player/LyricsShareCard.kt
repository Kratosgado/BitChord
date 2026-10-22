package com.music.bitchord.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.music.bitchord.ui.replay.Fonts
import com.music.bitchord.ui.replay.drawArtwork
import com.music.bitchord.ui.replay.drawBackdrop
import com.music.bitchord.ui.replay.drawLogo
import com.music.bitchord.ui.replay.ellipsised
import com.music.bitchord.ui.replay.loadBitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun renderLyricsShareCard(
    context: Context,
    card: LyricsShareRequest,
    story: Boolean = false,
): Bitmap = withContext(Dispatchers.Default) {
    val type = Fonts(context)
    val cover = card.artworkUrl?.let { loadBitmap(context, it) }

    val bodyTop = HEADER_BOTTOM + GAP_ABOVE_BODY
    val frameRoom = MAX_CARD_H - FOOTER_THUMB - FOOTER_PAD - GAP_BELOW_BODY - bodyTop
    val plan = fittestPlan(type, card.lines, frameRoom)
    val cardH = bodyTop + plan.content + GAP_BELOW_BODY + FOOTER_THUMB + FOOTER_PAD

    val outH = if (story) STORY_H else cardH.roundToInt().coerceAtMost(MAX_CARD_H.roundToInt())
    val thumbY: Float
    val lyricsStartY: Float
    if (story) {
        thumbY = outH - FOOTER_PAD - FOOTER_THUMB
        val areaTop = HEADER_BOTTOM + GAP_ABOVE_BODY
        val areaBottom = thumbY - GAP_BELOW_BODY
        lyricsStartY = areaTop + ((areaBottom - areaTop - plan.content) / 2f).coerceAtLeast(0f)
    } else {
        thumbY = cardH - FOOTER_PAD - FOOTER_THUMB
        lyricsStartY = bodyTop
    }

    val bitmap = Bitmap.createBitmap(CARD_W, outH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawLyricsBackdrop(canvas, cover, CARD_W, outH.toFloat())

    val brand = type.heading(46f, 0xE6FFFFFF.toInt()).apply {
        textAlign = Paint.Align.RIGHT
    }
    canvas.drawText(BRAND, CARD_W - CARD_MARGIN, HEADER_BASELINE, brand)
    drawLogo(
        canvas,
        context,
        CARD_W - CARD_MARGIN - brand.measureText(BRAND) - LOGO_GAP,
        HEADER_BASELINE,
    )

    var y = lyricsStartY
    for (row in plan.rows) {
        val baseline = y + row.gapBefore - row.paint.ascent() +
            if (row.isGap) row.paint.textSize * GAP_MARKER_OFFSET else 0f
        val x = if (row.centered) CARD_W / 2f else CARD_MARGIN
        canvas.drawText(row.text, x, baseline, row.paint)
        y += row.gapBefore + row.height
    }

    drawArtwork(canvas, cover, card.song.title, CARD_MARGIN, thumbY, FOOTER_THUMB, false)
    val textX = CARD_MARGIN + FOOTER_THUMB + 34f
    val room = CARD_W - CARD_MARGIN - textX
    val title = type.body(50f, Color.WHITE, bold = true)
    canvas.drawText(ellipsised(card.song.title, title, room), textX, thumbY + 74f, title)
    val artist = type.body(42f, 0x99FFFFFF.toInt())
    canvas.drawText(ellipsised(card.song.artist, artist, room), textX, thumbY + 132f, artist)

    bitmap
}

private fun drawLyricsBackdrop(
    canvas: Canvas,
    cover: Bitmap?,
    width: Int,
    height: Float,
) {
    if (cover == null) {
        drawBackdrop(canvas, null, 0f, height)
        return
    }
    val minSide = minOf(cover.width, cover.height)
    val cropX = (cover.width - minSide) / 2
    val cropY = (cover.height - minSide) / 2
    val src = Rect(cropX, cropY, cropX + minSide, cropY + minSide)
    val sampling = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    val widthF = width.toFloat()
    val frameAspect = widthF / height
    val fillRect = if (frameAspect >= 1f) {
        RectF(0f, (height - widthF) / 2f, widthF, (height + widthF) / 2f)
    } else {
        RectF((widthF - height) / 2f, 0f, (widthF + height) / 2f, height)
    }
    canvas.drawBitmap(cover, src, fillRect, sampling)

    val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
    scrim.color = BACKDROP_SCRIM
    canvas.drawRect(0f, 0f, widthF, height, scrim)
    scrim.shader = LinearGradient(
        0f,
        0f,
        0f,
        height,
        intArrayOf(0x80000000.toInt(), 0x40000000, 0xB3000000.toInt()),
        floatArrayOf(0f, 0.42f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, widthF, height, scrim)
}

// ── Fitting ──────────────────────────────────────────────────────────────────

private class Row(
    val text: String,
    val paint: Paint,
    val height: Float,
    val gapBefore: Float,
    /** Drawn centred rather than off the left edge — the gap marker. */
    val centered: Boolean = false,
    val isGap: Boolean = false,
)

/** The rows at one size, with everything needed to place them. */
private class Plan(val rows: List<Row>, val ascent: Float) {
    /** Distance from the top of the first line to the bottom of the last. */
    val content: Float
        get() = ascent + rows.fold(0f) { total, row -> total + row.gapBefore + row.height }
}

/**
 * The largest size on [FIT_LADDER] whose rows still fit in [room], or the
 * bottom rung when none of them do.
 *
 * The bottom rung is a floor rather than a last resort: under it the type stops
 * being worth sharing, so the frame grows to hold the words instead of the
 * words shrinking to hold the frame. That the player budgets characters at the
 * pick is what keeps this branch from ever being reached in practice.
 */
private fun fittestPlan(
    type: Fonts,
    lines: List<LyricsShareLine>,
    room: Float,
): Plan {
    for (size in FIT_LADDER) {
        val plan = planAt(type, lines, size)
        if (plan.content <= room) return plan
    }
    return planAt(type, lines, FIT_LADDER.last())
}

/**
 * The rows at one [size], set in the face the lyrics panel draws with.
 *
 * SF Pro Display Heavy with the panel's tight tracking and its line advance,
 * carried over as the same ratios the panel's 34sp headline uses — so the card
 * reads as a still of that panel rather than as a different app quoting it.
 * Compose's tracking is absolute and `Paint`'s is a fraction of the size, hence
 * the divisions: -0.7 over 34 for the words, -0.7 over 20 for the smaller line
 * the panel hangs underneath them.
 */
private fun planAt(type: Fonts, lines: List<LyricsShareLine>, size: Float): Plan {
    val primary = type.heading(size, Color.WHITE).apply { letterSpacing = TRACKING }
    val subSize = (size * SUB_RATIO).coerceAtLeast(SUB_FLOOR)
    val secondary = type.heading(subSize, SUB_COLOR).apply { letterSpacing = SUB_TRACKING }

    val rows = mutableListOf<Row>()
    lines.forEachIndexed { index, line ->
        if (line.isGap) {
            // The skipped lines, as "(...)" — the conventional editorial mark
            // for an omission. Hung off the left edge with the words, smaller
            // and a dim grey. Keep its row compact so the marker follows the
            // preceding lyric closely; the regular gap on the next lyric line
            // provides the separation below it.
            val marker = type.body(size * 0.65f, 0x66FFFFFF.toInt())
            val markerHeight = marker.descent() - marker.ascent()
            rows += Row("(...)", marker, markerHeight, 0f, isGap = true)
            return@forEachIndexed
        }
        var firstOfLine = true
        fun add(text: String, paint: Paint, height: Float) {
            // One verse line is one paragraph: its first row carries the space
            // above it and its wraps sit tight underneath, so a phrase reads as
            // a block rather than as a list of separate sentences.
            val gap = when {
                rows.isEmpty() -> 0f
                firstOfLine && index > 0 -> size * 0.55f
                else -> 0f
            }
            rows += Row(text, paint, height, gap)
            firstOfLine = false
        }
        wrap(line.text, primary, CARD_CONTENT_W).forEach { add(it, primary, size * LEAD) }
        val sub = line.subText
        if (!sub.isNullOrBlank() && sub != line.text) {
            wrap(sub, secondary, CARD_CONTENT_W).forEach { add(it, secondary, subSize * SUB_LEAD) }
        }
    }
    return Plan(rows, -primary.ascent())
}

// ── Text ─────────────────────────────────────────────────────────────────────

/**
 * Greedy wrap at [width], breaking wherever the line has to.
 *
 * Not a plain word-wrap: Japanese, Chinese and Korean lyrics carry no spaces at
 * all, and a wrapper that only split on spaces would hand the whole verse to
 * `drawText` and let it run off the card. `breakText` finds the character that
 * fits, and only then is the break walked back to a space — so scripts that keep
 * their words apart lose nothing, and scripts that don't still get several lines.
 */
private fun wrap(text: String, paint: Paint, width: Float): List<String> {
    val source = text.trim()
    if (source.isEmpty()) return listOf("")

    val out = mutableListOf<String>()
    var rest = source
    while (rest.isNotEmpty()) {
        val fit = paint.breakText(rest, true, width, null)
        if (fit <= 0) {
            out += rest
            break
        }
        // Walk back to the last space inside the fit, but only when something
        // still has to follow it — otherwise the fit is the whole string and
        // cutting at the space would drop the final word.
        val space = rest.lastIndexOf(' ', fit - 1)
        val take = if (space > 0 && fit < rest.length) space else fit
        if (take <= 0) {
            out += rest
            break
        }
        out += rest.substring(0, take).trimEnd()
        rest = rest.substring(take).trimStart()
    }
    return out.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
}

// ── Card geometry ────────────────────────────────────────────────────────────

private const val CARD_W = 1080

/**
 * 9:16 — a ceiling on the height, not the height itself. The card is grown to
 * its passage and only ever comes out this tall when the passage demands it.
 */
private const val MAX_CARD_H = 1920f

private const val STORY_H = 1920

private const val CARD_MARGIN = 72f
private const val CARD_CONTENT_W = CARD_W - CARD_MARGIN * 2

private const val LOGO_GAP = 20f
private const val BRAND = "BitChord"

/** Baseline of the mark at the top right, and just under it where the gap starts. */
private const val HEADER_BASELINE = 132f
private const val HEADER_BOTTOM = 170f

/** Air kept between the fixed furniture and the passage, both ends alike. */
private const val GAP_ABOVE_BODY = 20f
private const val GAP_BELOW_BODY = 20f

/** The sleeve square in the footer, and the air kept under it. */
private const val FOOTER_THUMB = 160f
private const val FOOTER_PAD = 96f

/** Smallest the translation line is allowed to get before it stops being legible. */
private const val SUB_FLOOR = 24f

/** Small visual correction so the omission marker sits below the line above. */
private const val GAP_MARKER_OFFSET = 0.18f

private const val BACKDROP_SCRIM = 0x66000000.toInt()

/**
 * The sizes tried, largest first.
 *
 * A ladder rather than a search: a dozen candidates is nothing to lay out, and
 * stepping by a visible amount keeps two cards with adjacent line counts from
 * coming out nearly the same size and reading as a mistake.
 *
 * The last rung doubles as the floor — see [fittestPlan] for why the frame is
 * what gives way under it rather than the type.
 */
private val FIT_LADDER = floatArrayOf(96f, 88f, 80f, 72f, 64f, 56f, 50f, 44f, 38f, 32f)

// ── The panel's type, as ratios ──────────────────────────────────────────────
// Every number here is one the lyrics panel uses, divided by the size it uses
// it at so it holds at whatever size the ladder lands on.

/** Tracking of the panel's headline: -0.7sp against its 34sp. */
private const val TRACKING = -0.7f / 34f

/** The same -0.7sp, against the 20sp it sets the line underneath in. */
private const val SUB_TRACKING = -0.7f / 20f

/** Line advance: 41sp over 34sp for the words, 25sp over 20sp for the sub. */
private const val LEAD = 41f / 34f
private const val SUB_LEAD = 25f / 20f

/** The sub-line against the words above it, and the white it is drawn in. */
private const val SUB_RATIO = 20f / 34f
private val SUB_COLOR = 0xD9FFFFFF.toInt()
