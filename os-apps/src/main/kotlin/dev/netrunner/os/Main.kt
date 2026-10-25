package dev.netrunner.os

import dev.netrunner.os.montage.montage
import dev.netrunner.os.pdf.pdf
import dev.netrunner.os.photos.photos
import dev.netrunner.os.sheet.tableur
import dev.netrunner.os.slides.presentation
import dev.netrunner.os.text.texte
import dev.netrunner.os.video.video
import dev.netrunner.os.web.web
import kotlin.system.exitProcess

/** netrunner-apps.jar <app> [files…]: each app of Netrunner OS has its launcher (os/apps). */
fun main(args: Array<String>) {
    val rest = args.drop(1)
    when (args.firstOrNull()) {
        "photos" -> photos(rest)
        "video" -> video(rest)
        "pdf" -> pdf(rest)
        "texte" -> texte(rest)
        "tableur" -> tableur(rest)
        "presentation" -> presentation(rest)
        "web" -> web(rest)
        "montage" -> montage(rest)
        else -> {
            System.err.println("usage : netrunner-apps <photos|video|pdf|texte|tableur|presentation|web|montage> [fichiers…]")
            exitProcess(2)
        }
    }
}
