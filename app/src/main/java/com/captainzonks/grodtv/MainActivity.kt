package com.captainzonks.grodtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                PlayerScreen(
                    videoUrl = HARDCODED_VIDEO_URL,
                    audioUrl = HARDCODED_AUDIO_URL,
                )
            }
        }
    }
}

@Composable
private fun PlayerScreen(videoUrl: String, audioUrl: String?) {
    val context = LocalContext.current

    val exoPlayer = remember {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("grod_tv/0.0.1")
            .setAllowCrossProtocolRedirects(true)

        val player = ExoPlayer.Builder(context).build()

        val source = if (audioUrl != null) {
            val v = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(videoUrl))
            val a = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(audioUrl))
            MergingMediaSource(v, a)
        } else {
            ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(videoUrl))
        }

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        player
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
    )
}

// Hardcoded Piped test pair (Rick Astley — Never Gonna Give You Up, 1080p H264 itag 137 + AAC itag 140).
// Resolved via tubeapi.zonks.org. googlevideo signatures expire ~6h after issue —
// re-resolve before each test run with:
//   curl -s https://tubeapi.zonks.org/streams/dQw4w9WgXcQ | jq '.videoStreams[]|select(.itag==137)|.url'
private const val HARDCODED_VIDEO_URL =
    "https://tubeproxy.zonks.org/videoplayback?bui=AbKmrwppe7Lty39oK01Jm0W1AsTXetFaHNIHZl9DRaZNblT912_GOXP2mnRQxYj5rOiZAEVbesCy1yr1&c=ANDROID&clen=80911999&cpn=5oP5m1kFKLidqVCI&cps=1295&dur=213.040&ei=-2wPase3H8bH-sAP9O7oiQI&expire=1779417435&fexp=51565115%2C51565681&fvip=4&gir=yes&host=rr1---sn-avobhvou-4oae.googlevideo.com&id=o-AJgSU1HsExbjk28tVt-QDPO7z0g0MH9j4mlDk4FRQq-E&initcwndbps=4548750&ip=8.44.151.219&itag=137&keepalive=yes&lmt=1766957926174250&lsig=APaTxxMwRAIgbEv6uJznck-WriEBXoAhRXRhdX9dz3VPieyjHHNQ8lwCICdueudeNbHwDkdJ1hjRFi8V_YmutbD8ZmES9XYNXTMm&lsparams=cps%2Cmet%2Cmh%2Cmm%2Cmn%2Cms%2Cmv%2Cmvi%2Cpl%2Crms%2Cinitcwndbps&met=1779395835%2C&mh=7c&mime=video%2Fmp4&mm=31%2C29&mn=sn-avobhvou-4oae%2Csn-u1qxo5-55&ms=au%2Crdu&mt=1779395358&mv=m&mvi=1&pl=23&qhash=ce960250&requiressl=yes&rms=au%2Cau&rqh=1&sig=AHEqNM4wRQIhANGJw420w0tnneD7L5IdKJEVz3IBGwQoLJDFh91_AhyhAiAdSxm4Y4E57XZzucK6Oj52hkW4R3WHH999Jy91h0rSHg%3D%3D&source=youtube&sparams=expire%2Cei%2Cip%2Cid%2Citag%2Csource%2Crequiressl%2Cxpc%2Cbui%2Cspc%2Cvprv%2Csvpuc%2Cmime%2Crqh%2Cgir%2Cclen%2Cdur%2Clmt&spc=96Xrv7qdxudrWh1Cy3R0NSV4bE1N2ifxJqSUYZR6VXV2orkFNQu0GGhM8hEw1bD24pXDZQ&svpuc=1&txp=5532534&vprv=1&xpc=EgVo2aDSNQ%3D%3D"

private val HARDCODED_AUDIO_URL: String? =
    "https://tubeproxy.zonks.org/videoplayback?bui=AbKmrwppe7Lty39oK01Jm0W1AsTXetFaHNIHZl9DRaZNblT912_GOXP2mnRQxYj5rOiZAEVbesCy1yr1&c=ANDROID&clen=3449447&cpn=5oP5m1kFKLidqVCI&cps=1295&dur=213.089&ei=-2wPase3H8bH-sAP9O7oiQI&expire=1779417435&fexp=51565115%2C51565681&fvip=4&gir=yes&host=rr1---sn-avobhvou-4oae.googlevideo.com&id=o-AJgSU1HsExbjk28tVt-QDPO7z0g0MH9j4mlDk4FRQq-E&initcwndbps=4548750&ip=8.44.151.219&itag=140&keepalive=yes&lmt=1766955925572207&lsig=APaTxxMwRAIgbEv6uJznck-WriEBXoAhRXRhdX9dz3VPieyjHHNQ8lwCICdueudeNbHwDkdJ1hjRFi8V_YmutbD8ZmES9XYNXTMm&lsparams=cps%2Cmet%2Cmh%2Cmm%2Cmn%2Cms%2Cmv%2Cmvi%2Cpl%2Crms%2Cinitcwndbps&met=1779395835%2C&mh=7c&mime=audio%2Fmp4&mm=31%2C29&mn=sn-avobhvou-4oae%2Csn-u1qxo5-55&ms=au%2Crdu&mt=1779395358&mv=m&mvi=1&pl=23&qhash=ee8ed757&requiressl=yes&rms=au%2Cau&rqh=1&sig=AHEqNM4wRQIhAJJEem-oGE_xqEV70I6iUGv2KeLIXGblZSdzW4dIW13PAiA92o0J_pvImtDJse9OppSH32qhpTgqedUnbGy_Mgr87Q%3D%3D&source=youtube&sparams=expire%2Cei%2Cip%2Cid%2Citag%2Csource%2Crequiressl%2Cxpc%2Cbui%2Cspc%2Cvprv%2Csvpuc%2Cmime%2Crqh%2Cgir%2Cclen%2Cdur%2Clmt&spc=96Xrv7qdxudrWh1Cy3R0NSV4bE1N2ifxJqSUYZR6VXV2orkFNQu0GGhM8hEw1bD24pXDZQ&svpuc=1&txp=5532534&vprv=1&xpc=EgVo2aDSNQ%3D%3D"
