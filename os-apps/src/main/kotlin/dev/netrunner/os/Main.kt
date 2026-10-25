package dev.netrunner.os

import dev.netrunner.os.photos.photos
import dev.netrunner.os.video.video
import kotlin.system.exitProcess

/** netrunner-apps.jar <app> [files…]: each app of Netrunner OS has its launcher (os/apps). */
fun main(args: Array<String>) {
    val rest = args.drop(1)
    when (args.firstOrNull()) {
        "photos" -> photos(rest)
        "video" -> video(rest)
        else -> {
            System.err.println("usage : netrunner-apps <photos|video> [fichiers…]")
            exitProcess(2)
        }
    }
}
