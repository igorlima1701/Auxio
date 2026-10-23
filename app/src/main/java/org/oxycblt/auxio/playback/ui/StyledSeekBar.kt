/*
 * Copyright (c) 2022 Auxio Project
 * StyledSeekBar.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.max
import org.oxycblt.auxio.databinding.ViewSeekBarBinding
import org.oxycblt.auxio.playback.formatDurationDs
import org.oxycblt.auxio.util.inflater
import timber.log.Timber as L

/**
 * A wrapper around [Slider] that shows position and duration values and sanitizes input to reduce
 * crashes from invalid values.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class StyledSeekBar
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ForcedLTRFrameLayout(context, attrs, defStyleAttr),
    Slider.OnSliderTouchListener,
    Slider.OnChangeListener {
    private val binding = ViewSeekBarBinding.inflate(context.inflater, this, true)

    init {
        binding.seekBarSlider.addOnSliderTouchListener(this)
        binding.seekBarSlider.addOnChangeListener(this)
        binding.seekBarSlider.setLabelFormatter { it.toLong().formatDurationDs(false) }
    }

    // --- Fork pessoal: segurar a bolinha = escolher o ponto e confirmar ("Ir para"/"Cancelar") ---

    private val longHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var movedEarly = false
    private var longMode = false
    private var confirming = false

    /** Quem mostra os botões "Ir para"/"Cancelar" (ficam no layout do player). */
    interface ConfirmHost {
        fun show(visible: Boolean)

        /** Qual botão está sob o dedo (coordenadas da tela): [GO], [CANCEL] ou [NONE]. */
        fun hit(rawX: Float, rawY: Float): Int

        /** Destaca o botão sob o dedo ([NONE] = nenhum). */
        fun highlight(which: Int)
    }

    /** Sem ele, segurar a bolinha funciona como arrastar normal. */
    var confirmHost: ConfirmHost? = null

    /** Ponto escolhido enquanto os botões estão à vista. */
    private var targetDs = 0L
    /** Último x do dedo dentro da barra (para o dedo que sobe até os botões não mexer no ponto). */
    private var lastInsideX = 0f

    /** Última posição mandada pelo player (para o "Cancelar" voltar a ela). */
    private var playerPositionDs = 0L
    private val enterLongMode = Runnable { startConfirm() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val host = confirmHost
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Um toque novo na barra desfaz a escolha anterior.
                if (confirming) finishConfirm(apply = false)
                downX = ev.x
                downY = ev.y
                lastInsideX = ev.x
                downTime = ev.eventTime
                movedEarly = false
                longMode = false
                // Ninguém acima (painel, fila) rouba o toque enquanto o dedo está na barra.
                parent?.requestDisallowInterceptTouchEvent(true)
                longHandler.postDelayed(enterLongMode, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!longMode && (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)) {
                    movedEarly = true
                    longHandler.removeCallbacks(enterLongMode)
                }
                if (confirming && host != null) {
                    if (ev.y < 0) {
                        // Dedo subiu até os botões: o ponto fica onde estava.
                        host.highlight(host.hit(ev.rawX, ev.rawY))
                        return true
                    }
                    host.highlight(NONE)
                    lastInsideX = ev.x
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                longHandler.removeCallbacks(enterLongMode)
                // Segurou parado e soltou antes do aviso disparar: vale como segurar.
                if (!longMode && !movedEarly && ev.eventTime - downTime >= LONG_PRESS_MS) {
                    startConfirm()
                }
                if (confirming && host != null && ev.y < 0) {
                    // Soltou em cima de um botão: encerra o arrasto da barra no último ponto e
                    // aciona o botão.
                    val which =
                        if (ev.actionMasked == MotionEvent.ACTION_UP) host.hit(ev.rawX, ev.rawY)
                        else NONE
                    host.highlight(NONE)
                    val end = MotionEvent.obtain(ev)
                    end.setLocation(lastInsideX, height / 2f)
                    super.dispatchTouchEvent(end)
                    end.recycle()
                    when (which) {
                        GO -> finishConfirm(apply = true)
                        CANCEL -> finishConfirm(apply = false)
                    }
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun startConfirm() {
        if (confirming) return
        val host = confirmHost ?: return
        longMode = true
        confirming = true
        isActivated = true
        targetDs = binding.seekBarSlider.value.toLong()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        binding.seekBarSlider.labelBehavior = LabelFormatter.LABEL_FLOATING
        binding.seekBarSlider.invalidate()
        host.show(true)
    }

    /** Fecha os botões; [apply] = vai para o ponto escolhido, senão volta para onde estava. */
    fun finishConfirm(apply: Boolean) {
        if (!confirming) return
        confirming = false
        longMode = false
        isActivated = false
        binding.seekBarSlider.labelBehavior = LabelFormatter.LABEL_GONE
        confirmHost?.highlight(NONE)
        confirmHost?.show(false)
        if (apply) {
            val target = targetDs.coerceIn(0, durationDs)
            binding.seekBarSlider.value = target.toFloat()
            binding.seekBarPosition.text = target.formatDurationDs(true)
            listener?.onSeekConfirmed(target)
        } else {
            val back = playerPositionDs.coerceIn(0, durationDs)
            binding.seekBarSlider.value = back.toFloat()
            binding.seekBarPosition.text = back.formatDurationDs(true)
        }
    }

    /** Põe os tempos nas pontas da barra, na mesma linha (como no Music OLED). */
    fun useInlineTimes() {
        val density = resources.displayMetrics.density
        val side = (60 * density).toInt()
        (binding.seekBarSlider.layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = side
            marginEnd = side
            bottomMargin = 0
            gravity = Gravity.CENTER_VERTICAL
        }
        for (view in listOf(binding.seekBarPosition, binding.seekBarDuration)) {
            (view.layoutParams as FrameLayout.LayoutParams).apply {
                gravity =
                    Gravity.CENTER_VERTICAL or
                        if (view === binding.seekBarPosition) Gravity.START else Gravity.END
                bottomMargin = 0
                marginStart = (12 * density).toInt()
                marginEnd = (12 * density).toInt()
            }
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        // Bolinha redonda (como no Music OLED) no lugar do tracinho, e trilha fina.
        binding.seekBarSlider.apply {
            val thumb = (18 * density).toInt()
            thumbWidth = thumb
            thumbHeight = thumb
            thumbTrackGapSize = 0
            trackHeight = (4 * density).toInt()
        }
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        longHandler.removeCallbacks(enterLongMode)
        if (confirming) finishConfirm(apply = false)
        super.onDetachedFromWindow()
    }

    /** Enables/disables wavy active-track rendering to match playback state. */
    fun setWaveEnabled(enabled: Boolean) {
        binding.seekBarSlider.setWaveEnabled(enabled)
    }

    /** The current [Listener] attached to this instance. */
    var listener: Listener? = null

    /**
     * The current position, in deci-seconds(1/10th of a second). This is the current value of the
     * SeekBar and is indicated by the start TextView in the layout.
     */
    var positionDs: Long
        get() = binding.seekBarSlider.value.toLong()
        set(value) {
            // Sanity check 1: Ensure that no negative values are sneaking their way into
            // this component.
            val from = max(value, 0)
            playerPositionDs = from
            // Sanity check 2: Ensure that this value is within the duration and will not crash
            // the app, and that the user is not currently seeking (which would cause the SeekBar
            // to jump around).
            if (from <= durationDs && !isActivated) {
                binding.seekBarSlider.value = from.toFloat()
                // We would want to keep this in the listener, but the listener only fires when
                // a value changes completely, and sometimes that does not happen with this view.
                binding.seekBarPosition.text = from.formatDurationDs(true)
            }
        }

    /**
     * The current duration, in deci-seconds (1/10th of a second). This is the end value of the
     * SeekBar and is indicated by the end TextView in the layout.
     */
    var durationDs: Long
        get() = binding.seekBarSlider.valueTo.toLong()
        set(value) {
            // Sanity check 1: If this is a value so low that it effectively rounds down to
            // zero, use 1 instead and disable the SeekBar.
            val to = max(value, 1)
            isEnabled = value > 0
            L.d("Value sanitization finished [to=$to, enabled=$isEnabled]")
            // Sanity check 2: If the current value exceeds the new duration value, clamp it
            // down so that we don't crash and instead have an annoying visual flicker.
            if (positionDs > to) {
                L.d("Clamping invalid position [current: $positionDs new max: $to]")
                binding.seekBarSlider.value = to.toFloat()
            }
            binding.seekBarSlider.valueTo = to.toFloat()
            binding.seekBarDuration.text = value.formatDurationDs(false)
        }

    override fun onStartTrackingTouch(slider: Slider) {
        L.d("Starting seek mode")
        // User has begun seeking, place the SeekBar into a "Suspended" mode in which no
        // position updates are sent and is indicated by the position value turning accented.
        isActivated = true
    }

    override fun onStopTrackingTouch(slider: Slider) {
        longHandler.removeCallbacks(enterLongMode)
        if (confirming) {
            // Fork pessoal: segurou a bolinha; só vai para o ponto com "Ir para".
            isActivated = true
            return
        }
        L.d("Confirming seek")
        // End of seek event, send off new value to listener.
        isActivated = false
        listener?.onSeekConfirmed(slider.value.toLong())
    }

    override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
        binding.seekBarPosition.text = value.toLong().formatDurationDs(true)
        if (confirming && fromUser) targetDs = value.toLong()
    }

    companion object {
        private const val LONG_PRESS_MS = 300L
        const val NONE = 0
        const val GO = 1
        const val CANCEL = 2
    }

    /** A listener for SeekBar interactions. */
    interface Listener {
        /**
         * Called when the internal [Slider] was scrubbed to a new position, requesting that a seek
         * be performed.
         *
         * @param positionDs The position to seek to, in deci-seconds (1/10th of a second).
         */
        fun onSeekConfirmed(positionDs: Long)
    }
}
