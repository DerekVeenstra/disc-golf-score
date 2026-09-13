package com.veenstra.discgolfscore

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [BackupClient.push]'s `deviceId` when nothing more specific is configured — provenance only, never used for merging (`CLOUD_SAVES.md` section 3). */
private const val DEFAULT_DEVICE_ID = "ticwatch"

/**
 * A 20-second budget for both connecting and reading (`CLOUD_SAVES.md` section 5 item 10): "a hung
 * request on a watch is indistinguishable from a crash." Long enough for a slow cell/BT-tethered
 * connection to a cold-started Apps Script (its first invocation in a while pays a real startup
 * cost), short enough that a genuinely dead connection doesn't leave `BACK UP NOW` looking frozen
 * forever.
 */
private const val TIMEOUT_MILLIS = 20_000

/**
 * The one real [BackupClient]: `HttpURLConnection` over plain HTTPS, no OAuth, no Google API
 * client (`CLOUD_SAVES.md` section 2 "Transport" — the whole reason this feature can exist as a
 * standalone Wear app at all). [url] and [secret] are bound at construction, read once from
 * [SyncConfig] by whichever [RoundViewModel] method needs them, so a config change can never leave
 * a stale client pointed at the old endpoint (see [BackupClient]'s own doc).
 *
 * All of it runs on [Dispatchers.IO] (`CLOUD_SAVES.md` section 5 item 10) — nothing here is safe
 * to call from the main thread, and nothing above this class needs to know that, since [push]/
 * [pull] are themselves `suspend` and already hop dispatchers internally.
 *
 * The one thing this class does that [BackupClient.kt]'s pure envelope functions can't: follow
 * Apps Script's redirect by hand. `/exec` always 301/302/307s a successful request to
 * `script.googleusercontent.com`, and `HttpURLConnection` will not cross that redirect on its own
 * for a POST — the platform's built-in redirect handling refuses to follow any redirect that
 * would change the request method, which POST→GET always does (`CLOUD_SAVES.md` section 5 items
 * 1-2). Skipping this is, per that section, "the single most common way this integration
 * 'mysteriously returns empty'": the POST completes, the server 302s, `HttpURLConnection` doesn't
 * follow it, and the app reads a zero-length body with no error at all. So [instanceFollowRedirects]
 * is explicitly turned off and the redirect is followed **once**, by hand, re-issued as a GET —
 * "budget one redirect hop, not a loop" (section 5 item 1): a second redirect off that GET is not
 * followed, and whatever comes back instead fails JSON parsing downstream rather than looping.
 */
class HttpBackupClient(
    private val url: String,
    private val secret: String,
    private val deviceId: String = DEFAULT_DEVICE_ID,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : BackupClient {

    override suspend fun push(data: BackupData): PushResult = withContext(Dispatchers.IO) {
        val requestBody = buildPushRequestBody(secret, deviceId, clock(), data)
        when (val response = postAndFollowRedirect(requestBody)) {
            is RawResponse.Body -> parsePushResponse(response.text)
            is RawResponse.HttpStatus -> PushResult.Failure(unexpectedStatusMessage(response.code))
            RawResponse.NoNetwork -> PushResult.Failure(NO_NETWORK_MESSAGE)
        }
    }

    override suspend fun pull(): PullResult = withContext(Dispatchers.IO) {
        val requestBody = buildPullRequestBody(secret, deviceId, clock())
        when (val response = postAndFollowRedirect(requestBody)) {
            is RawResponse.Body -> parsePullResponse(response.text)
            is RawResponse.HttpStatus -> PullResult.Failure(unexpectedStatusMessage(response.code))
            RawResponse.NoNetwork -> PullResult.Failure(NO_NETWORK_MESSAGE)
        }
    }

    private fun postAndFollowRedirect(body: String): RawResponse = try {
        val first = openConnection(url, method = "POST", body = body)
        when (val code = first.responseCode) {
            in 200..299 -> RawResponse.Body(first.readBodyText())
            301, 302, 307 -> {
                val location = first.getHeaderField("Location")
                if (location == null) {
                    RawResponse.HttpStatus(code)
                } else {
                    val redirected = openConnection(location, method = "GET", body = null)
                    if (redirected.responseCode in 200..299) {
                        RawResponse.Body(redirected.readBodyText())
                    } else {
                        RawResponse.HttpStatus(redirected.responseCode)
                    }
                }
            }
            else -> RawResponse.HttpStatus(code)
        }
    } catch (noNetwork: IOException) {
        // Every failure this integration can hit before a response is even read -- no wifi, no
        // BT-tethered phone (CLOUD_SAVES.md section 5 item 8), DNS failure on a mistyped URL, a
        // connect/read timeout -- surfaces as some IOException subtype. Reported uniformly as
        // "No network" rather than the exception's own (often cryptic) message.
        RawResponse.NoNetwork
    }

    private fun openConnection(target: String, method: String, body: String?): HttpURLConnection {
        val connection = URL(target).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        // Handled by hand in postAndFollowRedirect -- see this class's own doc comment for why
        // the platform's automatic version of this can't do the job for a POST.
        connection.instanceFollowRedirects = false
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return connection
    }

    private fun HttpURLConnection.readBodyText(): String = inputStream.bufferedReader().use { it.readText() }

    private fun unexpectedStatusMessage(code: Int): String = "Unexpected response (HTTP $code)"

    private companion object {
        const val NO_NETWORK_MESSAGE = "No network"
    }
}

/** The outcome of one HTTP round trip, before either [PushResult] or [PullResult] has an opinion about it. */
private sealed interface RawResponse {
    data class Body(val text: String) : RawResponse
    data class HttpStatus(val code: Int) : RawResponse
    data object NoNetwork : RawResponse
}
