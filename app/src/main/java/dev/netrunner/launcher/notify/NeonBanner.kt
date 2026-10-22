package dev.netrunner.launcher.notify

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import dev.netrunner.launcher.R
import dev.netrunner.launcher.ui.Cp
import dev.netrunner.launcher.ui.PanelCut

/**
 * Notifications from other apps shown as a banner in the faction's colours, drawn over
 * whatever is on screen (needs "display over other apps"). Tap opens the notification,
 * a swipe up dismisses it, it leaves on its own after a few seconds. Android's own pop-up
 * still shows unless floating notifications are turned off for that app in the system.
 */
object NeonBanner {
    private const val SHOW_MS = 5_000L
    private val handler = Handler(Looper.getMainLooper())
    private var current: View? = null
    private val hide = Runnable { dismiss() }

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    fun show(context: Context, item: FeedItem, header: String) {
        if (!canShow(context)) return
        val app = context.applicationContext
        handler.post {
            dismiss()
            val wm = app.getSystemService(WindowManager::class.java) ?: return@post
            val view = BannerView(app, item, header, onTap = {
                dismiss()
                NotificationHub.open(app, item)
            }, onSwipe = { dismiss() })
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP
                y = statusBarHeight(app) + dp(app, 6)
            }
            if (runCatching { wm.addView(view, params) }.isFailure) return@post
            current = view
            view.translationY = -dp(app, 120).toFloat()
            view.alpha = 0f
            view.animate().translationY(0f).alpha(1f).setDuration(180).start()
            handler.postDelayed(hide, SHOW_MS)
        }
    }

    /** Charger and low battery events, as a banner when the option is on. */
    fun power(context: Context, event: dev.netrunner.launcher.ui.FactionFeed.Power, level: Int) {
        dev.netrunner.launcher.ui.DeckSettings.load(context)
        if (!dev.netrunner.launcher.ui.DeckSettings.neonBanners) return
        if (context.getSystemService(android.os.PowerManager::class.java)?.isInteractive != true) return
        Cp.load(context)
        val (title, text) = dev.netrunner.launcher.ui.FactionFeed.power(Cp.faction, event, level)
        show(
            context,
            FeedItem(
                key = "netrunner-power", packageName = context.packageName, appLabel = "Netrunner",
                title = title, text = text, postTime = System.currentTimeMillis(), kind = FeedKind.Other,
                icon = null, contentIntent = null, replyAction = null, autoCancel = false, clearable = false,
            ),
            "◢ ${Cp.faction.label} // ÉNERGIE $level %",
        )
    }

    fun dismiss() {
        handler.removeCallbacks(hide)
        val view = current ?: return
        current = null
        val wm = view.context.getSystemService(WindowManager::class.java)
        view.animate().translationY(-view.height.toFloat()).alpha(0f).setDuration(140)
            .withEndAction { runCatching { wm?.removeView(view) } }
            .start()
    }

    private fun statusBarHeight(context: Context): Int {
        @SuppressLint("InternalInsetResource", "DiscouragedApi")
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(context, 28)
    }

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** The banner itself, drawn by hand: no Compose window outside an activity. */
    @SuppressLint("ViewConstructor")
    private class BannerView(
        context: Context,
        private val item: FeedItem,
        private val header: String,
        private val onTap: () -> Unit,
        private val onSwipe: () -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val primary = Cp.Yellow.toArgb()
        private val secondary = Cp.Cyan.toArgb()
        private val panel = Cp.Panel.copy(alpha = 0.97f).toArgb()
        private val textColor = Cp.Text.toArgb()
        private val dim = Cp.Dim.toArgb()
        private val cut = Cp.faction.cut
        private val bold = ResourcesCompat.getFont(context, R.font.rajdhani_bold) ?: Typeface.DEFAULT_BOLD
        private val mono = ResourcesCompat.getFont(context, R.font.share_tech_mono) ?: Typeface.MONOSPACE
        private val icon: Bitmap? = runCatching { item.icon?.asAndroidBitmap() }.getOrNull()

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
        private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 11f * density; color = primary }
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 16f * density; color = textColor }
        private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 13f * density; color = dim }
        private val shape = Path()

        private var downY = 0f
        private var swiped = false

        init {
            val side = (12 * density).toInt()
            setPadding(side, 0, side, 0)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (84 * density).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val l = paddingLeft.toFloat()
            val r = width - paddingRight.toFloat()
            val t = 2 * density
            val b = height - 2 * density
            val c = 12 * density
            shape.reset()
            // Same corner cuts as the faction's panels in the launcher.
            val cutTL = cut == PanelCut.Mirror || cut == PanelCut.Octagon || cut == PanelCut.Tab
            val cutTR = cut == PanelCut.Diagonal || cut == PanelCut.Octagon || cut == PanelCut.Tab || cut == PanelCut.Notch
            val cutBR = cut == PanelCut.Mirror || cut == PanelCut.Octagon
            val cutBL = cut == PanelCut.Diagonal || cut == PanelCut.Octagon
            shape.moveTo(l + if (cutTL) c else 0f, t)
            shape.lineTo(r - if (cutTR) c else 0f, t)
            shape.lineTo(r, t + if (cutTR) c else 0f)
            shape.lineTo(r, b - if (cutBR) c else 0f)
            shape.lineTo(r - if (cutBR) c else 0f, b)
            shape.lineTo(l + if (cutBL) c else 0f, b)
            shape.lineTo(l, b - if (cutBL) c else 0f)
            shape.lineTo(l, t + if (cutTL) c else 0f)
            shape.close()
            fill.color = panel
            canvas.drawPath(shape, fill)
            // Accent bar on the left, scanlines, border.
            fill.color = primary
            canvas.drawRect(l, t + c, l + 3 * density, b - c, fill)
            fill.color = 0x14000000
            var y = t
            while (y < b) { canvas.drawRect(l, y, r, y + 1, fill); y += 3 * density }
            stroke.color = primary
            canvas.drawPath(shape, stroke)

            var x = l + 14 * density
            icon?.let {
                val s = 36 * density
                val top = (height - s) / 2
                canvas.drawBitmap(it, null, RectF(x, top, x + s, top + s), null)
                stroke.color = secondary
                canvas.drawRect(x - 2, top - 2, x + s + 2, top + s + 2, stroke)
                x += s + 12 * density
            }
            val room = r - x - 12 * density
            fun fit(text: String, p: TextPaint) = TextUtils.ellipsize(text, p, room, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fit(header, headerPaint), x, t + 22 * density, headerPaint)
            canvas.drawText(fit(item.title.ifBlank { item.appLabel }, titlePaint), x, t + 44 * density, titlePaint)
            canvas.drawText(fit(item.text.replace('\n', ' '), bodyPaint), x, t + 64 * density, bodyPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; swiped = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (dy < 0) translationY = dy
                    if (dy < -24 * density) swiped = true
                }
                MotionEvent.ACTION_UP -> if (swiped) onSwipe() else { translationY = 0f; onTap() }
                MotionEvent.ACTION_CANCEL -> translationY = 0f
            }
            return true
        }
    }
}
