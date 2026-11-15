package app.orchard.tv.remote

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal keyboard used only to commit text typed on the phone. It is selected for the focused
 * editor by [RemoteController], commits the incoming text, and leaves the original keyboard alone.
 */
class RemoteInputService : InputMethodService() {
    @Volatile private var editing = false

    override fun onCreate() {
        super.onCreate()
        active = WeakReference(this)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editing = attribute != null && attribute.inputType != InputType.TYPE_NULL
    }

    override fun onFinishInput() {
        editing = false
        super.onFinishInput()
    }

    override fun onDestroy() {
        editing = false
        if (active.get() === this) active.clear()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View = LinearLayout(applicationContext).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(24, 12, 24, 12)
        setBackgroundColor(0xffe9ece9.toInt())
        addView(TextView(applicationContext).apply {
            text = "Luna TV"
            setTextColor(0xff35445a.toInt())
            textSize = 19f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(applicationContext).apply {
            text = "切换键盘"
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showInputMethodPicker()
            }
        })
    }

    private fun insert(text: String): Boolean {
        if (!editing || !RemoteText.isValid(text)) return false
        val connection = currentInputConnection ?: return false
        connection.beginBatchEdit()
        return try {
            connection.finishComposingText()
            connection.commitText(text, 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    companion object {
        @Volatile private var active = WeakReference<RemoteInputService>(null)

        val isActive: Boolean get() = active.get()?.editing == true

        /** Ask the system to show this keyboard so the focused editor binds its input connection. */
        fun requestShow() {
            Handler(Looper.getMainLooper()).post {
                runCatching { active.get()?.requestShowSelf(0) }
            }
        }

        fun commit(text: String, waitMs: Long = 2000L): Boolean {
            if (!RemoteText.isValid(text)) return false
            if (Looper.myLooper() == Looper.getMainLooper()) return active.get()?.insert(text) == true

            val handler = Handler(Looper.getMainLooper())
            val done = CountDownLatch(1)
            val finished = AtomicBoolean(false)
            val result = AtomicBoolean(false)
            val deadline = SystemClock.uptimeMillis() + waitMs

            val probe = object : Runnable {
                override fun run() {
                    if (finished.get()) return
                    val service = active.get()
                    if (service?.editing == true) {
                        if (finished.compareAndSet(false, true)) {
                            try { result.set(service.insert(text)) } catch (_: RuntimeException) {}
                            done.countDown()
                        }
                    } else if (SystemClock.uptimeMillis() < deadline) {
                        handler.postDelayed(this, 50)
                    } else if (finished.compareAndSet(false, true)) {
                        done.countDown()
                    }
                }
            }
            handler.post(probe)
            return try {
                done.await(waitMs + 250, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } finally {
                finished.set(true)
                handler.removeCallbacks(probe)
            } && result.get()
        }
    }
}

object RemoteText {
    const val MAX_LENGTH = 2000

    fun isValid(text: String): Boolean =
        text.isNotEmpty() && text.length <= MAX_LENGTH && !text.contains('\u0000')
}
