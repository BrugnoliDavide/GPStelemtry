package com.gpstelemetry.app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.gpstelemetry.core.Gate
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.PathPoint
import com.gpstelemetry.core.Sectors
import com.gpstelemetry.core.SplitGate
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackType
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tutorial: parte da solo al primo avvio e si può rivedere dal profilo.
 *
 * Passi: benvenuto → creare un tracciato → cronometrare → sessioni ed
 * Esplora → PROVA (premere ▶ su un tracciato di prova, che esiste solo qui
 * e non viene salvato) → SICUREZZA (premi play e non toccare più il
 * telefono, se non a veicolo fermo) → AVVERTENZE con "HO CAPITO".
 *
 * Le avvertenze vanno sempre accettate: "Salta" porta direttamente lì e,
 * finché non si preme "HO CAPITO", il tutorial ricompare al prossimo avvio.
 */
class TutorialActivity : AppCompatActivity() {

    companion object {
        /** Avviato dal profilo per rivederlo (non al primo avvio). */
        const val EXTRA_REPLAY = "replay"
    }

    private enum class Step { WELCOME, CREATE, TIMER, SESSIONS, TRY, SAFETY, DISCLAIMER }

    private val steps = Step.entries
    private var index = 0
    private val replay get() = intent.getBooleanExtra(EXTRA_REPLAY, false)
    private val d get() = resources.displayMetrics.density

    private lateinit var content: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var btnBack: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnSkip: View
    private var pulse: ValueAnimator? = null

    /** Tracciato di prova: esiste solo nel tutorial, non viene mai salvato. */
    private val demo: Track by lazy { demoTrack() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)
        content = findViewById(R.id.tutContent)
        dots = findViewById(R.id.tutDots)
        btnBack = findViewById(R.id.tutBack)
        btnNext = findViewById(R.id.tutNext)
        btnSkip = findViewById(R.id.tutSkip)

        index = savedInstanceState?.getInt("step") ?: 0
        btnBack.setOnClickListener { go(index - 1) }
        btnNext.setOnClickListener { onNext() }
        btnSkip.setOnClickListener { go(steps.indexOf(Step.DISCLAIMER)) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    index > 0 -> go(index - 1)
                    // Primo avvio: senza accettare le avvertenze si esce dall'app.
                    !replay && !Settings.tutorialDone(this@TutorialActivity) -> finishAffinity()
                    else -> finish()
                }
            }
        })
        show(animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", index)
    }

    /** Test: porta il tutorial a un passo preciso. */
    fun showStep(i: Int) {
        index = i.coerceIn(0, steps.size - 1)
        show(animate = false)
    }

    private fun go(i: Int) {
        if (i < 0 || i >= steps.size) return
        index = i
        show(animate = true)
    }

    private fun onNext() {
        if (steps[index] == Step.DISCLAIMER) {
            Settings.setTutorialDone(this, true)
            finish()
        } else {
            go(index + 1)
        }
    }

    /* ------------------------------ Rendering ---------------------------- */

    private fun show(animate: Boolean) {
        pulse?.cancel()
        pulse = null
        content.removeAllViews()
        val step = steps[index]
        when (step) {
            Step.WELCOME -> welcome()
            Step.CREATE -> create()
            Step.TIMER -> timer()
            Step.SESSIONS -> sessions()
            Step.TRY -> tryPlay()
            Step.SAFETY -> safety()
            Step.DISCLAIMER -> disclaimer()
        }
        findViewById<ScrollView>(R.id.tutScroll).scrollTo(0, 0)

        // Comandi.
        val last = step == Step.DISCLAIMER
        btnBack.visibility = if (index == 0 || last) View.INVISIBLE else View.VISIBLE
        btnBack.visibility = if (last) View.GONE else btnBack.visibility
        btnSkip.visibility = if (step.ordinal >= Step.SAFETY.ordinal) View.INVISIBLE else View.VISIBLE
        // Sul passo di prova si prosegue solo premendo ▶.
        btnNext.visibility = if (step == Step.TRY) View.INVISIBLE else View.VISIBLE
        btnNext.text = getString(
            when (step) {
                Step.DISCLAIMER -> R.string.tut_understood
                Step.WELCOME -> R.string.tut_start
                else -> R.string.tut_next
            }
        )
        renderDots(step)

        if (animate) {
            content.alpha = 0f
            content.translationX = 32 * d
            content.animate().alpha(1f).translationX(0f).setDuration(220).start()
        }
    }

    private fun renderDots(step: Step) {
        dots.removeAllViews()
        if (step == Step.DISCLAIMER) return
        val count = steps.size - 1
        for (i in 0 until count) {
            val active = i == index
            dots.addView(View(this).apply {
                setBackgroundResource(R.drawable.bg_pill)
                backgroundTintList = ContextCompat.getColorStateList(
                    this@TutorialActivity, if (active) R.color.accent else R.color.sector_pending,
                )
            }, LinearLayout.LayoutParams(((if (active) 22 else 8) * d).toInt(), (8 * d).toInt()).apply {
                marginEnd = (6 * d).toInt()
            })
        }
    }

    /* --------------------------- Mattoni grafici ------------------------- */

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun title(res: Int, top: Int = 24) = TextView(this).apply {
        setText(res)
        setTextAppearance(R.style.Text_Display)
        textSize = 30f
        setPadding(0, (top * d).toInt(), 0, 0)
    }.also { content.addView(it) }

    private fun body(res: Int) = TextView(this).apply {
        setText(res)
        textSize = 16f
        setLineSpacing(4 * d, 1f)
        setTextColor(color(R.color.text_secondary))
        setPadding(0, (10 * d).toInt(), 0, 0)
    }.also { content.addView(it) }

    /** Riquadro in vetro per le illustrazioni. */
    private fun glass(heightDp: Int, child: View): FrameLayout = FrameLayout(this).apply {
        setBackgroundResource(R.drawable.bg_glass)
        val p = (14 * d).toInt()
        setPadding(p, p, p, p)
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }.also {
        content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (heightDp * d).toInt()))
    }

    /** Icona grande in un cerchio. */
    private fun badge(iconRes: Int?, text: String? = null, bg: Int = R.drawable.bg_play_circle, tint: Int = R.color.on_accent) {
        val size = (112 * d).toInt()
        val circle = FrameLayout(this).apply { setBackgroundResource(bg) }
        if (iconRes != null) {
            circle.addView(ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ContextCompat.getColorStateList(this@TutorialActivity, tint)
            }, FrameLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt(), Gravity.CENTER))
        } else if (text != null) {
            circle.addView(TextView(this).apply {
                this.text = text
                textSize = 52f
                gravity = Gravity.CENTER
                setTextColor(color(tint))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        content.addView(circle, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    /** Riga "icona + testo" per gli elenchi. */
    private fun point(iconRes: Int, textRes: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * d).toInt(), 0, 0)
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ContextCompat.getColorStateList(this@TutorialActivity, R.color.accent)
        }, LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()))
        row.addView(TextView(this).apply {
            setText(textRes)
            textSize = 15f
            setTextColor(color(R.color.text_primary))
            setPadding((14 * d).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
    }

    /* -------------------------------- Passi ------------------------------ */

    private fun welcome() {
        badge(R.drawable.ic_timer)
        title(R.string.tut_welcome_title)
        body(R.string.tut_welcome_text)
        point(R.drawable.ic_add, R.string.tut_welcome_p1)
        point(R.drawable.ic_play, R.string.tut_welcome_p2)
        point(R.drawable.ic_share, R.string.tut_welcome_p3)
    }

    private fun create() {
        glass(220, TrackShapeView(this).apply {
            lineWidthDp = 3f
            setTrack(demo)
        })
        title(R.string.tut_create_title)
        body(R.string.tut_create_text)
        point(R.drawable.ic_satellite, R.string.tut_create_p1)
        point(R.drawable.ic_rotate_handle, R.string.tut_create_p2)
        point(R.drawable.ic_add, R.string.tut_create_p3)
    }

    private fun timer() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        box.addView(SectorBarView(this).apply {
            setSectors(
                listOf(
                    SectorBarView.Sector("S1", 21_400, Sectors.Rating.BEST),
                    SectorBarView.Sector("S2", 30_100, Sectors.Rating.BETTER),
                    SectorBarView.Sector("S3", 32_900, Sectors.Rating.WORSE),
                )
            )
        })
        box.addView(TextView(this).apply {
            text = Fmt.time(84_400)
            setTextAppearance(R.style.Text_Clock)
            textSize = 54f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.sector_better))
        })
        glass(210, box)
        title(R.string.tut_timer_title)
        body(R.string.tut_timer_text)
        legend(R.color.sector_best, R.string.tut_legend_best)
        legend(R.color.sector_better, R.string.tut_legend_better)
        legend(R.color.sector_worse, R.string.tut_legend_worse)
    }

    private fun legend(colorRes: Int, textRes: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        row.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_pill)
            backgroundTintList = ContextCompat.getColorStateList(this@TutorialActivity, colorRes)
        }, LinearLayout.LayoutParams((34 * d).toInt(), (12 * d).toInt()))
        row.addView(TextView(this).apply {
            setText(textRes)
            textSize = 15f
            setTextColor(color(R.color.text_primary))
            setPadding((14 * d).toInt(), 0, 0, 0)
        })
        content.addView(row)
    }

    private fun sessions() {
        badge(R.drawable.ic_history)
        title(R.string.tut_sessions_title)
        body(R.string.tut_sessions_text)
        point(R.drawable.ic_history, R.string.tut_sessions_p1)
        point(R.drawable.ic_share, R.string.tut_sessions_p2)
        point(R.drawable.ic_explore, R.string.tut_sessions_p3)
    }

    /** Prova: scheda del tracciato di prova con il tasto ▶ che pulsa. */
    private fun tryPlay() {
        title(R.string.tut_try_title, top = 8)
        body(R.string.tut_try_text)
        val card = LayoutInflater.from(this).inflate(R.layout.item_track, content, false)
        card.findViewById<TrackShapeView>(R.id.trackShape).apply {
            lineWidthDp = 2f
            setTrack(demo)
        }
        card.findViewById<TextView>(R.id.trackName).setText(R.string.tut_demo_name)
        card.findViewById<TextView>(R.id.trackType).setText(R.string.track_type_circular)
        card.findViewById<TextView>(R.id.trackLength).text = Fmt.dist(Geo.pathLength(demo.path))
        card.findViewById<TextView>(R.id.trackInfo).text = resources.getQuantityString(
            R.plurals.track_info_fmt, demo.splits.size + 1, demo.tolerance.toInt(), demo.splits.size + 1,
        )
        card.findViewById<View>(R.id.trackBest).visibility = View.GONE
        card.findViewById<View>(R.id.trackCommunity).visibility = View.GONE
        val play = card.findViewById<ImageButton>(R.id.btnPlay)
        play.setOnClickListener { go(index + 1) }
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (28 * d).toInt()
        })
        content.addView(TextView(this).apply {
            setText(R.string.tut_try_hint)
            textSize = 14f
            gravity = Gravity.END
            setTextColor(color(R.color.accent))
            setPadding(0, (10 * d).toInt(), (8 * d).toInt(), 0)
        })
        // Il tasto play pulsa per invitare a premerlo.
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            play,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.14f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.14f),
        ).apply {
            duration = 650
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun safety() {
        badge(null, text = "✋", bg = R.drawable.bg_offtrack, tint = android.R.color.white)
        title(R.string.tut_safety_title)
        body(R.string.tut_safety_text)
        point(R.drawable.ic_play, R.string.tut_safety_p1)
        point(R.drawable.ic_timer, R.string.tut_safety_p2)
        point(R.drawable.ic_stop, R.string.tut_safety_p3)
    }

    /** Avvertenze: da accettare con "HO CAPITO". */
    private fun disclaimer() {
        badge(null, text = "⚠", bg = R.drawable.bg_offtrack, tint = android.R.color.white)
        title(R.string.tut_disclaimer_title)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glass)
            val p = (18 * d).toInt()
            setPadding(p, p / 2, p, p)
        }
        listOf(
            R.string.tut_disclaimer_1,
            R.string.tut_disclaimer_2,
            R.string.tut_disclaimer_3,
            R.string.tut_disclaimer_4,
            R.string.tut_disclaimer_5,
        ).forEach { res ->
            box.addView(TextView(this).apply {
                text = "•  " + getString(res)
                textSize = 15f
                setLineSpacing(3 * d, 1f)
                setTextColor(color(R.color.text_primary))
                setPadding(0, (10 * d).toInt(), 0, 0)
            })
        }
        content.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16 * d).toInt()
        })
        content.addView(TextView(this).apply {
            setText(R.string.tut_disclaimer_footer)
            textSize = 13f
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(color(R.color.text_secondary))
            setPadding(0, (12 * d).toInt(), 0, 0)
        })
    }

    /* --------------------------- Tracciato di prova ---------------------- */

    private fun demoTrack(): Track {
        val lat0 = 45.0
        val mLat = 111_320.0
        val mLon = mLat * cos(lat0 * PI / 180)
        fun xy(u: Double): Pair<Double, Double> {
            val th = -u * 2 * PI
            val r = 260 * (1 + 0.3 * sin(2 * th) + 0.1 * cos(3 * th))
            return r * cos(th) to r * sin(th)
        }
        fun ll(u: Double) = xy(u).let { (x, y) -> lat0 + y / mLat to 9.0 + x / mLon }
        fun gate(u: Double): Gate {
            val (ax, ay) = xy(u)
            val (bx, by) = xy(u + 0.001)
            val bearing = (atan2(bx - ax, by - ay) * 180 / PI + 360) % 360
            val (lat, lon) = ll(u)
            return Gate(lat, lon, (bearing + 90) % 180, 40.0)
        }
        return Track(
            id = "tutorial-demo", name = getString(R.string.tut_demo_name), type = TrackType.CIRCULAR,
            tolerance = 15.0, createdAt = 0, start = gate(0.0), finish = null,
            splits = mutableListOf(SplitGate("P1", gate(1 / 3.0)), SplitGate("P2", gate(2 / 3.0))),
            path = MutableList(240) { i -> ll(i / 240.0).let { PathPoint(it.first, it.second) } },
        )
    }

    override fun onDestroy() {
        pulse?.cancel()
        super.onDestroy()
    }
}
