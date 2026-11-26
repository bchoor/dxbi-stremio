package com.notdxbi.dxbi.tv.marquee

import org.json.JSONObject

/**
 * A source, read from what its add-on says about it (name, title, file name).
 * Ranked: 4K > 1080p > 720p, then Dolby Vision > HDR > SDR, then Dolby sound (DTS last, still listed),
 * then disc > web. Cached (instant) sources come first, since the others only start a download.
 */
class Source(val addon: Addon, val json: JSONObject) {
    val url: String = json.optString("url")
    val name: String = json.optString("name")
    private val text: String = listOf(name, json.optString("title"), json.optString("description")).joinToString("\n")
    val fileName: String = json.optJSONObject("behaviorHints")?.optString("filename")?.ifEmpty { null }
        ?: text.lines().map { it.trim() }.firstOrNull { Regex("""\.(mkv|mp4|avi|ts|m2ts)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        ?: json.optString("title").lines().firstOrNull().orEmpty().ifEmpty { json.optString("description").lines().firstOrNull().orEmpty() }
    private val all = "$text\n$fileName"

    private fun has(re: String) = Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(all)

    val instant: Boolean = run {
        val first = name.lines().firstOrNull().orEmpty()
        val bracket = Regex("""^\s*\[([^\]]+)]""").find(first)?.groupValues?.get(1)
        (bracket != null && Regex("""\+|⚡|instant|cached""", RegexOption.IGNORE_CASE).containsMatchIn(bracket)) ||
            Regex("""⚡|\binstant\b|\bcached\b""", RegexOption.IGNORE_CASE).containsMatchIn(first)
    }
    /** A debrid link that isn't cached: playing it starts a download instead */
    val pending: Boolean = !instant && Regex("""\[(?:RD|AD|PM|TB|DL|ED|OC)\b[^\]]*]""", RegexOption.IGNORE_CASE).containsMatchIn(name)

    val res: Int = when {
        has("""\b(?:2160p|4k|uhd)\b""") -> 2160
        has("""\b1440p\b""") -> 1440
        has("""\b(?:1080p|fhd)\b""") -> 1080
        has("""\b720p\b""") -> 720
        has("""\b(?:576p|480p|360p|sd|dvdrip|xvid)\b""") -> 480
        else -> 0
    }

    val kind: String = when {
        has("""\bremux\b""") -> "Remux"
        has("""\b(?:blu-?ray|bdrip|brrip|bdremux|bd25|bd50)\b""") -> "BluRay"
        has("""\bweb[ .-]?(?:dl|rip)?\b""") -> "WEB"
        has("""\bhdtv\b""") -> "HDTV"
        has("""\b(?:cam|hdcam|camrip|telesync|hdts|ts|tc)\b""") -> "CAM"
        else -> ""
    }

    val dv: Boolean = has("""\b(?:dv|dovi|dolby[ .-]?vision)\b""")
    /** Blu-ray Dolby Vision is profile 7 (two layers): this TV plays its HDR10 picture instead */
    val dv7: Boolean = dv && (kind == "Remux" || kind == "BluRay") && !has("""\b(?:p8|dv ?8|profile ?8)\b""")
    val hdr: Boolean = dv || has("""\bhdr(?:10\+?)?\b""")
    val imax: Boolean = has("""\bimax\b""")
    /** Which cut of the film it is, when the name says (a plain release says nothing) */
    val edition: String = when {
        has("""\bextended\b""") -> "EXTENDED"
        has("""\bdirector'?s?[ .-]?cut\b|\bdc\b""") -> "DIRECTOR'S CUT"
        has("""\bfinal[ .-]?cut\b""") -> "FINAL CUT"
        has("""\bunrated\b""") -> "UNRATED"
        has("""\buncut\b""") -> "UNCUT"
        has("""\bspecial[ .-]?edition\b""") -> "SPECIAL EDITION"
        has("""\bcriterion\b""") -> "CRITERION"
        has("""\btheatrical\b""") -> "THEATRICAL"
        else -> ""
    }
    val remastered: Boolean = has("""\bremaster(?:ed)?\b""")
    val lesser: Boolean = has("""\b(?:ai[ ._-]?upscal\w*|upscal\w*|\d{2,3}fps|hfr|3d|h?sbs)\b""")

    val audio: String = run {
        val atmos = has("""\batmos\b""")
        var a = when {
            has("""\btrue[ .-]?hd""") -> "TrueHD"
            has("""\b(?:ddp|dd\+|e-?ac-?3|dolby[ .-]?digital[ .-]?plus)""") -> "DD+"
            has("""\bdts[ .-]?(?:hd|ma|x)""") -> "DTS-HD"
            has("""\bdts\b""") -> "DTS"
            has("""\b(?:dd|ac-?3|dolby[ .-]?digital)[ .]?(?:[257]\.[01])?\b""") -> "DD"
            has("""\bflac\b""") -> "FLAC"
            has("""\b(?:aac|opus)\b""") -> "AAC"
            atmos -> "DD+"
            else -> ""
        }
        if (atmos && a.isNotEmpty()) a += " Atmos"
        a
    }

    /** Sound the soundbar can't take (on ARC: Dolby Digital and DD+ only) is converted on the TV */
    val converted: Boolean = audio.startsWith("TrueHD") || audio.startsWith("DTS")

    val sizeBytes: Double = Regex("""(\d+(?:[.,]\d+)?)\s*(TiB|GiB|MiB|TB|GB|MB)\b""", RegexOption.IGNORE_CASE).find(all)?.let { m ->
        val n = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
        n * when (m.groupValues[2].uppercase()) { "TIB" -> 1099511627776.0; "GIB" -> 1073741824.0; "MIB" -> 1048576.0; "TB" -> 1e12; "GB" -> 1e9; else -> 1e6 }
    } ?: 0.0

    val size: String = if (sizeBytes <= 0) "" else if (sizeBytes >= 1e9) "%.1f GB".format(sizeBytes / 1e9) else "%.0f MB".format(sizeBytes / 1e6)

    val picture: Int = when { dv && !dv7 -> 3; hdr -> 2; else -> 0 }

    val sound: Int = when (audio) {
        "DD+ Atmos" -> 9; "DD+" -> 8; "TrueHD Atmos" -> 7; "DD" -> 6; "TrueHD" -> 5; "FLAC" -> 4; "AAC" -> 3; "" -> 2
        else -> 1 // DTS of any kind: last, but listed
    }

    private val kindScore: Int = when (kind) { "Remux" -> 4; "BluRay" -> 3; "WEB" -> 2; "HDTV" -> 1; "CAM" -> -50; else -> 0 }
    private val resTier: Int = when { res >= 2160 -> 4; res >= 1440 -> 3; res >= 1080 -> 2; res >= 720 -> 1; else -> 0 }

    val score: Double = (if (instant) 1e7 else 0.0) + (if (pending) -1e7 else 0.0) +
        resTier * 1e6 + picture * 1e5 + sound * 1e4 + kindScore * 1e3 +
        (sizeBytes / 1e9).coerceAtMost(99.0) - (if (lesser) 5e6 else 0.0)

    /** The picture, for the card's left tile under the resolution */
    val pictureLabel: String = when {
        dv7 -> "HDR10"
        dv -> "DV"
        has("""\bhdr10\+|\bhdr10plus\b""") -> "HDR10+"
        hdr -> "HDR"
        else -> ""
    }

    /** Short badges for the card, in reading order (the resolution and picture are on the tile) */
    fun badges(): List<Pair<String, Badge>> = buildList {
        if (edition.isNotEmpty()) add(edition to Badge.SOLID)
        if (remastered) add("REMASTERED" to Badge.LINE)
        if (dv7) add("DOLBY VISION DISC · PLAYS AS HDR10" to Badge.LINE)
        // sound: ✓ when the soundbar gets it as it is, → DD+ when it's converted
        if (audio.isNotEmpty()) add(if (converted) "${audio.uppercase()} → DD+ 5.1" to Badge.WARN else "${audio.uppercase()} ✓" to Badge.GO)
        if (imax) add("IMAX" to Badge.GOLD)
        if (kind == "Remux") add("REMUX" to Badge.LINE) else if (kind == "BluRay") add("BLU-RAY" to Badge.LINE) else if (kind == "WEB") add("WEB" to Badge.LINE)
        if (instant) add("⚡ INSTANT" to Badge.GOLD)
        if (pending) add("DOWNLOAD FIRST" to Badge.WARN)
    }

    val resLabel: String = when { res >= 2160 -> "4K"; res > 0 -> "${res}p"; else -> "?" }

    /** Which add-on it came from, and the service inside it where it says (Easynews, RD) */
    val from: String = run {
        val first = name.lines().firstOrNull().orEmpty().replace(Regex("""\s+"""), " ").trim()
        if (first.isEmpty() || first.equals(addon.name, true)) addon.name else "${addon.name} · ${first.take(28)}"
    }

    /** Where it really comes from: Torrentio, or the service an add-on like AIOStreams got it from */
    val provider: String = run {
        val service = listOf("Easynews" to "easynews", "TorBox" to "torbox", "Real-Debrid" to "real-?debrid|\\[rd", "AllDebrid" to "alldebrid|\\[ad",
            "Premiumize" to "premiumize|\\[pm", "Debrid-Link" to "debrid-?link|\\[dl", "Usenet" to "usenet|nzb")
            .firstOrNull { Regex(it.second, RegexOption.IGNORE_CASE).containsMatchIn(name) }?.first
        when {
            addon.name.contains("torrentio", true) -> if (service != null && service != "Easynews") "Torrentio · $service" else "Torrentio"
            service != null -> service
            else -> addon.name
        }
    }

    /** One colour per provider, so they can be told apart at a glance */
    val providerColor: Int = when {
        provider.startsWith("Torrentio") -> 0xFF5AA9FF.toInt()
        provider.startsWith("Easynews") -> 0xFF6FE3BC.toInt()
        provider.startsWith("TorBox") -> 0xFFC79BFF.toInt()
        else -> 0xFFFFD9C2.toInt()
    }

    /** What the player keeps this source's audio choice under */
    val key: String = json.optString("infoHash").takeIf { it.isNotEmpty() }?.let { "$it/${json.optInt("fileIdx", 0)}" }
        ?: fileName.ifEmpty { url.substringBefore('?') }

    val headers: JSONObject? = json.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
}

enum class Badge { SOLID, LINE, WARN, GOLD, GO }
