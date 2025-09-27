package io.texne.g1.basis.client

import android.content.Context
import android.content.ServiceConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import kotlin.collections.any
import kotlin.collections.forEach
import kotlin.collections.map
import kotlin.collections.plus
import kotlin.text.repeat

const val SPACE_FILL_MULTIPLIER = 2

abstract class G1ServiceCommon<ServiceInterface> constructor(
    private val context: Context
): Closeable {

    enum class GlassesStatus { UNINITIALIZED, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    data class Glasses(
        val id: String?,
        val name: String?,
        val status: GlassesStatus,
        // Battery information is optional in the AIDL contract (missing or -1),
        // so we surface it as nullable to avoid misleading callers.
        val batteryPercentage: Int?
    )

    enum class ServiceStatus { READY, LOOKING, LOOKED, ERROR }

    data class State(
        val status: ServiceStatus,
        val glasses: List<Glasses>
    )

    protected val writableState =
        MutableStateFlow<State?>(null)

    val state = writableState.asStateFlow()

    protected var service: ServiceInterface? = null
    abstract protected val serviceConnection: ServiceConnection

    //

    override fun close() {
        context.unbindService(serviceConnection)
    }

    //

    fun listConnectedGlasses(): List<Glasses> =
        state.value?.glasses?.filter { it.status == GlassesStatus.CONNECTED } ?: listOf()

    //

    enum class JustifyLine { LEFT, RIGHT, CENTER }
    enum class JustifyPage { TOP, BOTTOM, CENTER }

    data class FormattedLine(
        val text: String,
        val justify: JustifyLine
    )

    data class FormattedPage(
        val lines: List<FormattedLine>,
        val justify: JustifyPage
    )

    data class TimedFormattedPage(
        val page: FormattedPage,
        val milliseconds: Long
    )

    suspend fun displayFormattedPageSequence(id: String, sequence: List<TimedFormattedPage>): Boolean {
        sequence.forEach { timedPage ->
            if(!displayFormattedPage(id, timedPage.page)) {
                return false
            }
            delay(timedPage.milliseconds)
        }
        return stopDisplaying(id)
    }

    suspend fun displayTimedFormattedPage(id: String, timedFormattedPage: TimedFormattedPage): Boolean {
        if(!displayFormattedPage(id, timedFormattedPage.page)) {
            return false
        }
        delay(timedFormattedPage.milliseconds)
        return stopDisplaying(id)
    }

    suspend fun displayCentered(id: String, lines: List<String>, milliseconds: Long? = 2000): Boolean {
        return if(milliseconds == null) displayFormattedPage(id, FormattedPage(
            justify = JustifyPage.CENTER,
            lines = lines.map { FormattedLine(text = it, justify = JustifyLine.CENTER) }
        )) else displayTimedFormattedPage( id,
            TimedFormattedPage(
                page = FormattedPage(
                    justify = JustifyPage.CENTER,
                    lines = lines.map { FormattedLine(text = it, justify = JustifyLine.CENTER) }
                ),
                milliseconds = milliseconds
            )
        )
    }

    suspend fun displayFormattedPage(id: String, formattedPage: FormattedPage): Boolean {
        if(formattedPage.lines.size > 5 || formattedPage.lines.any { it.text.length > 40 }) {
            return false
        }
        val renderedLines = formattedPage.lines.map {
            when(it.justify) {
                JustifyLine.LEFT -> it.text
                JustifyLine.CENTER -> " ".repeat((SPACE_FILL_MULTIPLIER*(40 - it.text.length)/2).toInt()).plus(it.text)
                JustifyLine.RIGHT -> " ".repeat((SPACE_FILL_MULTIPLIER*(40 - it.text.length)).toInt()).plus(it.text)
            }
        }
        val renderedPage: List<String> = when(formattedPage.lines.size) {
            5 -> renderedLines
            4 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus("")
                JustifyPage.BOTTOM -> listOf("").plus(renderedLines)
                JustifyPage.CENTER -> renderedLines.plus("")
            }
            3 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus(listOf("", ""))
                JustifyPage.BOTTOM -> listOf("", "").plus(renderedLines)
                JustifyPage.CENTER -> listOf("").plus(renderedLines).plus("")
            }
            2 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus(listOf("", "", ""))
                JustifyPage.BOTTOM -> listOf("", "", "").plus(renderedLines)
                JustifyPage.CENTER -> listOf("").plus(renderedLines).plus(listOf("", ""))
            }
            1 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus(listOf("", "", "", ""))
                JustifyPage.BOTTOM -> listOf("", "", "", "").plus(renderedLines)
                JustifyPage.CENTER -> listOf("", "").plus(renderedLines).plus(listOf("", ""))
            }
            else -> listOf("", "", "", "", "")
        }
        return displayTextPage(id, renderedPage)
    }

    suspend fun displayTimedTextPage(id: String, page: List<String>, milliseconds: Long): Boolean {
        if(!displayTextPage(id, page)) {
            return false
        }
        delay(milliseconds)
        return stopDisplaying(id)
    }

    abstract suspend fun displayTextPage (id: String, page: List<String>): Boolean
    abstract suspend fun stopDisplaying(id: String):  Boolean
}