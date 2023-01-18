package com.paranid5.mediastreamer.youtube_extractor

import android.content.Context
import android.util.SparseArray
import com.evgenii.jsevaluator.JsEvaluator
import com.evgenii.jsevaluator.interfaces.JsCallback
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeExtractor(
    ctx: Context,
    onExtractionComplete: (SparseArray<YouTubeFile>, VideoMeta) -> Unit
) {
    private var videoID: String? = null
    private var videoMeta: VideoMeta? = null
    private val cacheDirPath = ctx.cacheDir.absolutePath

    @Volatile
    private var decipheredSignature: String? = null

    /**
     * Start the extraction
     * @param youtubeLink the youtube page link or video id
     */

    fun extract(youtubeLink: String): Result<SparseArray<YouTubeFile>?> {
        videoID = null

        var matcher = patYouTubePageLink.matcher(youtubeLink)

        when {
            matcher.find() -> videoID = matcher.group(3)

            else -> {
                matcher = patYouTubeShortLink.matcher(youtubeLink)

                when {
                    matcher.find() -> videoID = matcher.group(3)
                    youtubeLink.matches("\\p{Graph}+?".toRegex()) -> videoID = youtubeLink
                }
            }
        }

        return when {
            videoID != null ->
                try {
                    Result.success(streamUrls)
                } catch (e: Exception) {
                    Result.failure(e)
                }

            else -> Result.failure(WrongYouTubeUrlFormatException())
        }
    }

    // FORMAT_STREAM_TYPE_OTF(otf=1) requires downloading the init fragment (adding
    // `&sq=0` to the URL) and parsing emsg box to determine the number of fragment that
    // would subsequently requested with (`&sq=N`) (cf. youtube-dl)

    private val streamUrls: SparseArray<Any>?
        private get() {
            val pageHtml: String
            val encSignatures = SparseArray<String>()
            val ytFiles: SparseArray<YtFile> = SparseArray<YtFile>()
            var reader: BufferedReader? = null
            var urlConnection: HttpURLConnection? = null
            val getUrl = URL("https://youtube.com/watch?v=$videoID")
            try {
                urlConnection = getUrl.openConnection() as HttpURLConnection
                urlConnection!!.setRequestProperty("User-Agent", USER_AGENT)
                reader = BufferedReader(InputStreamReader(urlConnection.inputStream))
                val sbPageHtml = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sbPageHtml.append(line)
                }
                pageHtml = sbPageHtml.toString()
            } finally {
                reader?.close()
                urlConnection?.disconnect()
            }
            var mat = patPlayerResponse.matcher(pageHtml)
            if (mat.find()) {
                val ytPlayerResponse = JSONObject(mat.group(1))
                val streamingData = ytPlayerResponse.getJSONObject("streamingData")
                val formats = streamingData.getJSONArray("formats")
                for (i in 0 until formats.length()) {
                    val format = formats.getJSONObject(i)

                    // FORMAT_STREAM_TYPE_OTF(otf=1) requires downloading the init fragment (adding
                    // `&sq=0` to the URL) and parsing emsg box to determine the number of fragment that
                    // would subsequently requested with (`&sq=N`) (cf. youtube-dl)
                    val type = format.optString("type")
                    if ((type != null) && type == "FORMAT_STREAM_TYPE_OTF") continue
                    val itag = format.getInt("itag")
                    if (FORMAT_MAP[itag] != null) {
                        if (format.has("url")) {
                            val url = format.getString("url").replace("\\u0026", "&")
                            ytFiles.append(itag, YtFile(FORMAT_MAP[itag], url))
                        } else if (format.has("signatureCipher")) {
                            mat = patSigEncUrl.matcher(format.getString("signatureCipher"))
                            val matSig = patSignature.matcher(format.getString("signatureCipher"))
                            if (mat.find() && matSig.find()) {
                                val url = URLDecoder.decode(mat.group(1), "UTF-8")
                                val signature = URLDecoder.decode(matSig.group(1), "UTF-8")
                                ytFiles.append(itag, YtFile(FORMAT_MAP[itag], url))
                                encSignatures.append(itag, signature)
                            }
                        }
                    }
                }
                val adaptiveFormats = streamingData.getJSONArray("adaptiveFormats")
                for (i in 0 until adaptiveFormats.length()) {
                    val adaptiveFormat = adaptiveFormats.getJSONObject(i)
                    val type = adaptiveFormat.optString("type")
                    if ((type != null) && type == "FORMAT_STREAM_TYPE_OTF") continue
                    val itag = adaptiveFormat.getInt("itag")
                    if (FORMAT_MAP[itag] != null) {
                        if (adaptiveFormat.has("url")) {
                            val url = adaptiveFormat.getString("url").replace("\\u0026", "&")
                            ytFiles.append(itag, YtFile(FORMAT_MAP[itag], url))
                        } else if (adaptiveFormat.has("signatureCipher")) {
                            mat = patSigEncUrl.matcher(adaptiveFormat.getString("signatureCipher"))
                            val matSig =
                                patSignature.matcher(adaptiveFormat.getString("signatureCipher"))
                            if (mat.find() && matSig.find()) {
                                val url = URLDecoder.decode(mat.group(1), "UTF-8")
                                val signature = URLDecoder.decode(matSig.group(1), "UTF-8")
                                ytFiles.append(itag, YtFile(FORMAT_MAP[itag], url))
                                encSignatures.append(itag, signature)
                            }
                        }
                    }
                }
                val videoDetails = ytPlayerResponse.getJSONObject("videoDetails")
                videoMeta = VideoMeta(
                    videoDetails.getString("videoId"),
                    videoDetails.getString("title"),
                    videoDetails.getString("author"),
                    videoDetails.getString("channelId"),
                    videoDetails.getString("lengthSeconds").toLong(),
                    videoDetails.getString("viewCount").toLong(),
                    videoDetails.getBoolean("isLiveContent"),
                    videoDetails.getString("shortDescription")
                )
            } else {
                Log.d(LOG_TAG, "ytPlayerResponse was not found")
            }
            if (encSignatures.size() > 0) {
                val curJsFileName: String
                if (CACHING
                    && (((decipherJsFileName == null) || decipherFunctions == null) || decipherFunctionName == null)
                ) {
                    readDecipherFunctFromCache()
                }
                mat = patDecryptionJsFile.matcher(pageHtml)
                if (!mat.find()) mat = patDecryptionJsFileWithoutSlash.matcher(pageHtml)
                if (mat.find()) {
                    curJsFileName = mat.group(0).replace("\\/", "/")
                    if (decipherJsFileName == null || decipherJsFileName != curJsFileName) {
                        decipherFunctions = null
                        decipherFunctionName = null
                    }
                    decipherJsFileName = curJsFileName
                }
                if (LOGGING) Log.d(
                    LOG_TAG,
                    "Decipher signatures: " + encSignatures.size() + ", videos: " + ytFiles.size()
                )
                val signature: String?
                decipheredSignature = null
                if (decipherSignature(encSignatures)) {
                    lock.lock()
                    try {
                        jsExecuting.await(7, TimeUnit.SECONDS)
                    } finally {
                        lock.unlock()
                    }
                }
                signature = decipheredSignature
                if (signature == null) {
                    return null
                } else {
                    val sigs = signature.split("\n".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()
                    var i = 0
                    while (i < encSignatures.size() && i < sigs.size) {
                        val key = encSignatures.keyAt(i)
                        var url: String = ytFiles[key].getUrl()
                        url += "&sig=" + sigs[i]
                        val newFile = YtFile(FORMAT_MAP[key], url)
                        ytFiles.put(key, newFile)
                        i++
                    }
                }
            }
            if (ytFiles.size() == 0) {
                if (LOGGING) Log.d(LOG_TAG, pageHtml)
                return null
            }
            return ytFiles
        }

    @Throws(IOException::class)
    private fun decipherSignature(encSignatures: SparseArray<String>): Boolean {
        // Assume the functions don't change that much
        if (decipherFunctionName == null || decipherFunctions == null) {
            val decipherFunctUrl = "https://youtube.com" + decipherJsFileName
            var reader: BufferedReader? = null
            val javascriptFile: String
            val url = URL(decipherFunctUrl)
            val urlConnection = url.openConnection() as HttpURLConnection
            urlConnection.setRequestProperty("User-Agent", USER_AGENT)
            try {
                reader = BufferedReader(InputStreamReader(urlConnection.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                    sb.append(" ")
                }
                javascriptFile = sb.toString()
            } finally {
                reader?.close()
                urlConnection.disconnect()
            }
            if (LOGGING) Log.d(
                LOG_TAG,
                "Decipher FunctURL: $decipherFunctUrl"
            )
            var mat = patSignatureDecFunction.matcher(javascriptFile)
            if (mat.find()) {
                decipherFunctionName = mat.group(1)
                if (LOGGING) Log.d(LOG_TAG, "Decipher Functname: " + decipherFunctionName)
                val patMainVariable = Pattern.compile(
                    "(var |\\s|,|;)" + decipherFunctionName.replace("$", "\\$") +
                            "(=function\\((.{1,3})\\)\\{)"
                )
                var mainDecipherFunct: String
                mat = patMainVariable.matcher(javascriptFile)
                if (mat.find()) {
                    mainDecipherFunct = "var " + decipherFunctionName + mat.group(2)
                } else {
                    val patMainFunction = Pattern.compile(
                        ("function " + decipherFunctionName.replace("$", "\\$") +
                                "(\\((.{1,3})\\)\\{)")
                    )
                    mat = patMainFunction.matcher(javascriptFile)
                    if (!mat.find()) return false
                    mainDecipherFunct = "function " + decipherFunctionName + mat.group(2)
                }
                var startIndex = mat.end()
                var braces = 1
                var i = startIndex
                while (i < javascriptFile.length) {
                    if (braces == 0 && startIndex + 5 < i) {
                        mainDecipherFunct += javascriptFile.substring(startIndex, i) + ";"
                        break
                    }
                    if (javascriptFile[i] == '{') braces++ else if (javascriptFile[i] == '}') braces--
                    i++
                }
                decipherFunctions = mainDecipherFunct
                // Search the main function for extra functions and variables
                // needed for deciphering
                // Search for variables
                mat = patVariableFunction.matcher(mainDecipherFunct)
                while (mat.find()) {
                    val variableDef = "var " + mat.group(2) + "={"
                    if (decipherFunctions!!.contains(variableDef)) {
                        continue
                    }
                    startIndex = javascriptFile.indexOf(variableDef) + variableDef.length
                    var braces = 1
                    var i = startIndex
                    while (i < javascriptFile.length) {
                        if (braces == 0) {
                            decipherFunctions += variableDef + javascriptFile.substring(
                                startIndex,
                                i
                            ) + ";"
                            break
                        }
                        if (javascriptFile[i] == '{') braces++ else if (javascriptFile[i] == '}') braces--
                        i++
                    }
                }
                // Search for functions
                mat = patFunction.matcher(mainDecipherFunct)
                while (mat.find()) {
                    val functionDef = "function " + mat.group(2) + "("
                    if (decipherFunctions!!.contains(functionDef)) {
                        continue
                    }
                    startIndex = javascriptFile.indexOf(functionDef) + functionDef.length
                    var braces = 0
                    var i = startIndex
                    while (i < javascriptFile.length) {
                        if (braces == 0 && startIndex + 5 < i) {
                            decipherFunctions += functionDef + javascriptFile.substring(
                                startIndex,
                                i
                            ) + ";"
                            break
                        }
                        if (javascriptFile[i] == '{') braces++ else if (javascriptFile[i] == '}') braces--
                        i++
                    }
                }
                if (LOGGING) Log.d(LOG_TAG, "Decipher Function: " + decipherFunctions)
                decipherViaWebView(encSignatures)
                if (CACHING) {
                    writeDeciperFunctToChache()
                }
            } else {
                return false
            }
        } else {
            decipherViaWebView(encSignatures)
        }
        return true
    }

    private fun readDecipherFunctFromCache() {
        val cacheFile = File(cacheDirPath + "/" + CACHE_FILE_NAME)
        // The cached functions are valid for 2 weeks
        if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified()) < 1209600000) {
            var reader: BufferedReader? = null
            try {
                reader = BufferedReader(InputStreamReader(FileInputStream(cacheFile), "UTF-8"))
                decipherJsFileName = reader.readLine()
                decipherFunctionName = reader.readLine()
                decipherFunctions = reader.readLine()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (reader != null) {
                    try {
                        reader.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun writeDeciperFunctToChache() {
        val cacheFile = File(cacheDirPath + "/" + CACHE_FILE_NAME)
        var writer: BufferedWriter? = null
        try {
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(cacheFile), "UTF-8"))
            writer.write(decipherJsFileName + "\n")
            writer.write(decipherFunctionName + "\n")
            writer.write(decipherFunctions)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (writer != null) {
                try {
                    writer.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun decipherViaWebView(encSignatures: SparseArray<String>) {
        val context = refContext.get() ?: return
        val stb = StringBuilder(decipherFunctions + " function decipher(")
        stb.append("){return ")
        for (i in 0 until encSignatures.size()) {
            val key = encSignatures.keyAt(i)
            if (i < encSignatures.size() - 1) stb.append(decipherFunctionName).append("('").append(
                encSignatures[key]
            ).append("')+\"\\n\"+") else stb.append(decipherFunctionName).append("('").append(
                encSignatures[key]
            ).append("')")
        }
        stb.append("};decipher();")
        Handler(Looper.getMainLooper()).post {
            JsEvaluator(context).evaluate(stb.toString(), object : JsCallback {
                override fun onResult(result: String) {
                    lock.lock()
                    try {
                        decipheredSignature = result
                        jsExecuting.signal()
                    } finally {
                        lock.unlock()
                    }
                }

                override fun onError(errorMessage: String) {
                    lock.lock()
                    try {
                        if (LOGGING) Log.e(
                            LOG_TAG,
                            errorMessage
                        )
                        jsExecuting.signal()
                    } finally {
                        lock.unlock()
                    }
                }
            })
        }
    }

    companion object {
        var CACHING = true
        var LOGGING = false
        private val LOG_TAG = "YouTubeExtractor"
        private val CACHE_FILE_NAME = "decipher_js_funct"
        private var decipherJsFileName: String? = null
        private var decipherFunctions: String? = null
        private var decipherFunctionName: String? = null
        private val USER_AGENT =
            "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/97.0.4692.98 Safari/537.36"
        private val patYouTubePageLink =
            Pattern.compile("(http|https)://(www\\.|m.|)youtube\\.com/watch\\?v=(.+?)( |\\z|&)")
        private val patYouTubeShortLink =
            Pattern.compile("(http|https)://(www\\.|)youtu.be/(.+?)( |\\z|&)")
        private val patPlayerResponse =
            Pattern.compile("var ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\})\\s*;")
        private val patSigEncUrl = Pattern.compile("url=(.+?)(\\u0026|$)")
        private val patSignature = Pattern.compile("s=(.+?)(\\u0026|$)")
        private val patVariableFunction =
            Pattern.compile("([{; =])([a-zA-Z$][a-zA-Z0-9$]{0,2})\\.([a-zA-Z$][a-zA-Z0-9$]{0,2})\\(")
        private val patFunction = Pattern.compile("([{; =])([a-zA-Z$_][a-zA-Z0-9$]{0,2})\\(")
        private val patDecryptionJsFile = Pattern.compile("\\\\/s\\\\/player\\\\/([^\"]+?)\\.js")
        private val patDecryptionJsFileWithoutSlash = Pattern.compile("/s/player/([^\"]+?).js")
        private val patSignatureDecFunction =
            Pattern.compile("(?:\\b|[^a-zA-Z0-9$])([a-zA-Z0-9$]{1,4})\\s*=\\s*function\\(\\s*a\\s*\\)\\s*\\{\\s*a\\s*=\\s*a\\.split\\(\\s*\"\"\\s*\\)")
        private val FORMAT_MAP: SparseArray<Format?> = SparseArray<Format?>()

        init {
            // http://en.wikipedia.org/wiki/YouTube#Quality_and_formats

            // Video and Audio
            FORMAT_MAP.put(
                17,
                Format(17, "3gp", 144, Format.VCodec.MPEG4, Format.ACodec.AAC, 24, false)
            )
            FORMAT_MAP.put(
                36,
                Format(36, "3gp", 240, Format.VCodec.MPEG4, Format.ACodec.AAC, 32, false)
            )
            FORMAT_MAP.put(
                5,
                Format(5, "flv", 240, Format.VCodec.H263, Format.ACodec.MP3, 64, false)
            )
            FORMAT_MAP.put(
                43,
                Format(43, "webm", 360, Format.VCodec.VP8, Format.ACodec.VORBIS, 128, false)
            )
            FORMAT_MAP.put(
                18,
                Format(18, "mp4", 360, Format.VCodec.H264, Format.ACodec.AAC, 96, false)
            )
            FORMAT_MAP.put(
                22,
                Format(22, "mp4", 720, Format.VCodec.H264, Format.ACodec.AAC, 192, false)
            )

            // Dash Video
            FORMAT_MAP.put(
                160,
                Format(160, "mp4", 144, Format.VCodec.H264, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                133,
                Format(133, "mp4", 240, Format.VCodec.H264, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                134,
                Format(134, "mp4", 360, Format.VCodec.H264, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                135,
                Format(135, "mp4", 480, Format.VCodec.H264, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                136,
                Format(136, "mp4", 720, Format.VCodec.H264, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                137,
                Format(137, "mp4", 1080, Format.VCodec.H264, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                264,
                Format(264, "mp4", 1440, Format.VCodec.H264, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                266,
                Format(266, "mp4", 2160, Format.VCodec.H264, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                298,
                Format(298, "mp4", 720, Format.VCodec.H264, 60, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                299,
                Format(299, "mp4", 1080, Format.VCodec.H264, 60, Format.ACodec.NONE, true)
            )

            // Dash Audio
            FORMAT_MAP.put(
                140,
                Format(140, "m4a", Format.VCodec.NONE, Format.ACodec.AAC, 128, true)
            )
            FORMAT_MAP.put(
                141,
                Format(141, "m4a", Format.VCodec.NONE, Format.ACodec.AAC, 256, true)
            )
            FORMAT_MAP.put(
                256,
                Format(256, "m4a", Format.VCodec.NONE, Format.ACodec.AAC, 192, true)
            )
            FORMAT_MAP.put(
                258,
                Format(258, "m4a", Format.VCodec.NONE, Format.ACodec.AAC, 384, true)
            )

            // WEBM Dash Video
            FORMAT_MAP.put(
                278,
                Format(278, "webm", 144, Format.VCodec.VP9, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                242,
                Format(242, "webm", 240, Format.VCodec.VP9, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                243,
                Format(243, "webm", 360, Format.VCodec.VP9, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                244,
                Format(244, "webm", 480, Format.VCodec.VP9, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                247,
                Format(247, "webm", 720, Format.VCodec.VP9, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                248,
                Format(248, "webm", 1080, Format.VCodec.VP9, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                271,
                Format(271, "webm", 1440, Format.VCodec.VP9, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                313,
                Format(313, "webm", 2160, Format.VCodec.VP9, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                302,
                Format(302, "webm", 720, Format.VCodec.VP9, 60, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                308,
                Format(308, "webm", 1440, Format.VCodec.VP9, 60, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                303,
                Format(303, "webm", 1080, Format.VCodec.VP9, 60, Format.ACodec.NONE, true)
            )
            FORMAT_MAP.put(
                315,
                Format(315, "webm", 2160, Format.VCodec.VP9, 60, Format.ACodec.NONE, true)
            )

            // WEBM Dash Audio
            FORMAT_MAP.put(
                171,
                Format(171, "webm", Format.VCodec.NONE, Format.ACodec.VORBIS, 128, true)
            )
            FORMAT_MAP.put(
                249,
                Format(249, "webm", Format.VCodec.NONE, Format.ACodec.OPUS, 48, true)
            )
            FORMAT_MAP.put(
                250,
                Format(250, "webm", Format.VCodec.NONE, Format.ACodec.OPUS, 64, true)
            )
            FORMAT_MAP.put(
                251,
                Format(251, "webm", Format.VCodec.NONE, Format.ACodec.OPUS, 160, true)
            )

            // HLS Live Stream
            FORMAT_MAP.put(
                91,
                Format(91, "mp4", 144, Format.VCodec.H264, Format.ACodec.AAC, 48, false, true)
            )
            FORMAT_MAP.put(
                92,
                Format(92, "mp4", 240, Format.VCodec.H264, Format.ACodec.AAC, 48, false, true)
            )
            FORMAT_MAP.put(
                93,
                Format(93, "mp4", 360, Format.VCodec.H264, Format.ACodec.AAC, 128, false, true)
            )
            FORMAT_MAP.put(
                94,
                Format(94, "mp4", 480, Format.VCodec.H264, Format.ACodec.AAC, 128, false, true)
            )
            FORMAT_MAP.put(
                95,
                Format(95, "mp4", 720, Format.VCodec.H264, Format.ACodec.AAC, 256, false, true)
            )
            FORMAT_MAP.put(
                96,
                Format(96, "mp4", 1080, Format.VCodec.H264, Format.ACodec.AAC, 256, false, true)
            )
        }
    }
}