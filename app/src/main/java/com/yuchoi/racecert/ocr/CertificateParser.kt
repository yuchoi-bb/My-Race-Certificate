package com.yuchoi.racecert.ocr

import com.yuchoi.racecert.data.RaceType
import java.time.LocalDate

/**
 * 기록증 OCR 텍스트에서 대회명/날짜/기록/거리/종목을 추정한다.
 * 완벽하지 않으므로 결과는 "초안"으로 채우고 사용자가 확인·수정하는 것을 전제로 한다.
 */
object CertificateParser {

    data class Parsed(
        val title: String? = null,
        val date: LocalDate? = null,
        val recordTime: String? = null,
        val distance: String? = null,
        val type: RaceType? = null,
    )

    // 완주 시간 후보. 디지털 폰트에서 0→O, 1→I 로 오인식되는 경우까지 허용해서 잡는다.
    private val timeTokenRegex = Regex("""([0-9OoIl]{1,2}):([0-9OoIl]{2}):([0-9OoIl]{2})""")
    private val distanceKmRegex = Regex("""(\d{1,3}(?:\.\d+)?)\s?[Kk][Mm]?\b""")
    private val isoDateRegex = Regex("""(\d{4})\s*[.\-/]\s*(\d{1,2})\s*[.\-/]\s*(\d{1,2})""")
    private val koreanDateRegex = Regex("""(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일""")
    private val monthNameDateRegex = Regex(
        """([A-Za-z]{3,9})\.?\s+(\d{1,2}),?\s+(\d{4})""",
    )

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    // 한국어 대회명(가장 구체적). 영문 부제보다 우선한다.
    private val strongTitleKeywords = listOf(
        "마라톤", "철인", "트라이애슬론", "그란폰도",
        "FONDO", "IRONMAN", "TRIATHLON",
    )
    private val fallbackTitleKeywords = listOf("MARATHON", "RUN", "RACE", "대회")

    fun parse(text: String): Parsed {
        if (text.isBlank()) return Parsed()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        return Parsed(
            title = findTitle(lines),
            date = findDate(text),
            recordTime = findRecordTime(text),
            distance = findDistance(text),
            type = findType(text),
        )
    }

    private fun findTitle(lines: List<String>): String? {
        lines.firstOrNull { line -> strongTitleKeywords.any { line.contains(it, ignoreCase = true) } }
            ?.let { return it }
        lines.firstOrNull { line ->
            fallbackTitleKeywords.any { line.contains(it, ignoreCase = true) } &&
                !line.contains("CERTIFICATE", ignoreCase = true) &&
                !line.contains("RECORD", ignoreCase = true)
        }?.let { return it }
        return lines.firstOrNull { it.length in 4..40 }
    }

    private fun normalizeDigits(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                'O', 'o' -> '0'
                'I', 'l' -> '1'
                else -> c
            },
        )
    }

    private fun findRecordTime(text: String): String? {
        for (m in timeTokenRegex.findAll(text)) {
            val h = normalizeDigits(m.groupValues[1]).toIntOrNull() ?: continue
            val min = normalizeDigits(m.groupValues[2]).toIntOrNull() ?: continue
            val sec = normalizeDigits(m.groupValues[3]).toIntOrNull() ?: continue
            // 마라톤 완주 시간은 0~29시간 범위. 집결/출발 시각(HH:MM)은 3그룹이 아니라 걸리지 않는다.
            if (h in 0..29 && min in 0..59 && sec in 0..59) {
                return "%02d:%02d:%02d".format(h, min, sec)
            }
        }
        return null
    }

    private fun findDistance(text: String): String? {
        when {
            text.contains("하프") ||
                Regex("""\bhalf\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> return "하프"
            text.contains("풀코스") || text.contains("풀") ||
                Regex("""\bfull\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                text.contains("42.195") -> return "풀코스"
        }
        return distanceKmRegex.find(text)?.value?.replace(" ", "")
    }

    private fun findType(text: String): RaceType? = when {
        Regex("""triathlon|ironman|철인|트라이애슬론""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text) -> RaceType.TRIATHLON
        Regex("""granfondo|gran\s*fondo|그란폰도""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text) -> RaceType.GRANFONDO
        Regex("""marathon|마라톤|\brun\b|running""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text) -> RaceType.MARATHON
        else -> null
    }

    private fun findDate(text: String): LocalDate? {
        koreanDateRegex.find(text)?.let { m ->
            runCatching {
                return LocalDate.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                )
            }
        }
        isoDateRegex.find(text)?.let { m ->
            runCatching {
                return LocalDate.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                )
            }
        }
        monthNameDateRegex.find(text)?.let { m ->
            val month = months[m.groupValues[1].take(3).lowercase()]
            if (month != null) {
                runCatching {
                    return LocalDate.of(
                        m.groupValues[3].toInt(),
                        month,
                        m.groupValues[2].toInt(),
                    )
                }
            }
        }
        return null
    }
}
