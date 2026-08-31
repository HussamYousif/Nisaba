package com.nisaba.llm

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.R
import com.nisaba.ink.InkSurfaceView
import com.nisaba.ink.TestActivity
import com.nisaba.notebook.NotebookStore
import com.nisaba.notebook.model.Notebook
import com.nisaba.ui.NotebookScreen
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * THE core loop, end-to-end on-device (docs/09 §3): stylus strokes → idle
 * summon → mock DeepSeek → parsed answer persisted + rendered below the page;
 * and cancel-on-resume when the user keeps writing.
 */
@RunWith(AndroidJUnit4::class)
class SummonFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var store: NotebookStore
    private lateinit var settings: AppSettings
    private lateinit var notebook: Notebook
    private lateinit var screen: NotebookScreen
    private lateinit var scenario: ActivityScenario<TestActivity>

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        com.nisaba.magic.Fonts.load(ctx.assets) // TestActivity skips MainActivity
        store = NotebookStore(ctx)
        settings = AppSettings(ctx).apply {
            apiKey = "test-key"
            baseUrl = server.url("/").toString().trimEnd('/')
            idlePauseSeconds = 1
        }
        notebook = store.createBlank()
        notebook.title = "Summon Test"

        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { activity ->
            screen = NotebookScreen(activity).apply {
                bind(notebook, settings, onBack = {}, onSave = { store.save(it) })
            }
            activity.setContentView(screen)
        }
        waitFor({
            var laidOut = false
            scenario.onActivity { laidOut = it.findViewById<InkSurfaceView>(R.id.inkView).width > 0 }
            laidOut
        }, 5_000)
    }

    @After
    fun tearDown() {
        scenario.close()
        server.shutdown()
    }

    private fun inkView(): InkSurfaceView {
        var v: InkSurfaceView? = null
        scenario.onActivity { v = it.findViewById<InkSurfaceView>(R.id.inkView) }
        return v!!
    }

    private fun answerSection(): LinearLayout {
        var v: LinearLayout? = null
        scenario.onActivity { v = it.findViewById<LinearLayout>(R.id.answerSection) }
        return v!!
    }

    private fun completionJson(content: String) =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":"${content.replace("\"", "\\\"")}"}}]}"""

    private fun dispatchStroke(view: InkSurfaceView, points: List<Pair<Float, Float>>) {
        for ((i, p) in points.withIndex()) {
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val coords = MotionEvent.PointerCoords().apply {
                x = p.first; y = p.second; pressure = 0.6f; size = 0.6f
            }
            val props = MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS
            }
            val ev = MotionEvent.obtain(
                100L, 200L + i, action, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0
            )
            view.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    private fun waitFor(condition: () -> Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    // ------------------------------------------------------------ tests

    @Test
    fun idleSummon_answersAndPersistsBelowThePage() {
        server.enqueue(
            MockResponse().setBody(
                completionJson("""{"blocks":[{"type":"text","markdown":"**The answer is 42.** And it is written in ink."}]}""")
            )
        )
        scenario.onActivity { dispatchStroke(inkView(), listOf(100f to 200f, 400f to 350f, 700f to 250f)) }

        // Idle fires after 1 s → request → parse → render.
        val answered = waitFor({ notebook.pages[0].answers.any { it.status == "complete" } }, 20_000)
        assertTrue("answer never arrived", answered)

        val group = notebook.pages[0].answers.first { it.status == "complete" }
        assertEquals("text", group.blocks[0].type)
        assertTrue(group.blocks[0].markdown!!.contains("42"))

        // The answer is rendered below the page in the UI.
        val rendered = waitFor({ answerSection().childCount >= 1 }, 5_000)
        assertTrue("answer section did not render", rendered)

        // Transcript: user + assistant entries.
        assertEquals(2, notebook.transcript.size)
        assertEquals("assistant", notebook.transcript.last().role)
        assertEquals(group.id, notebook.transcript.last().answerGroupId)

        // Persisted: autosave flushes, reload shows the same answer.
        val persisted = waitFor({
            store.load(notebook.id).pages[0].answers.any { it.id == group.id }
        }, 10_000)
        assertTrue("answer not persisted", persisted)
        assertEquals("complete", store.load(notebook.id).pages[0].answers.first { it.id == group.id }.status)
    }

    @Test
    fun writingWhilePending_cancelsTheSummon() {
        // Slow scribe: 4 s before answering.
        server.enqueue(
            MockResponse()
                .setBodyDelay(4, TimeUnit.SECONDS)
                .setBody(completionJson("""{"blocks":[{"type":"text","markdown":"late"}]}"""))
        )
        scenario.onActivity { dispatchStroke(inkView(), listOf(100f to 200f, 400f to 350f)) }

        // Wait until the request is actually in flight.
        val sent = runCatching { server.takeRequest(10, TimeUnit.SECONDS) }.getOrNull()
        assertTrue("request was never sent", sent != null)

        // The user keeps writing → cancel-on-resume (F3.2).
        scenario.onActivity { dispatchStroke(inkView(), listOf(800f to 400f, 900f to 500f)) }

        val cancelled = waitFor({ notebook.pages[0].answers.any { it.status == "cancelled" } }, 10_000)
        assertTrue("summon was not cancelled by writing", cancelled)

        // And no complete answer may appear afterwards.
        Thread.sleep(4_500) // past the mock's 4 s delay
        assertTrue(notebook.pages[0].answers.none { it.status == "complete" })
    }

    @Test
    fun mixedAnswer_drawingAndTable_renderInTheAnswerSection() {
        // Single-line, valid JSON (the model's content must be a valid JSON
        // string). The second enqueue covers the repair-retry path.
        val blocks = "{\"blocks\":[{\"type\":\"text\",\"markdown\":\"**A table of spells:**\"}," +
            "{\"type\":\"table\",\"title\":\"Incantations\",\"headers\":[\"Spell\",\"Effect\"]," +
            "\"rows\":[[\"Lumos\",\"Light\"],[\"Nox\",\"Darkness\"]]}," +
            "{\"type\":\"drawing\",\"svg\":\"<svg viewBox='0 0 1000 1000'><path d='M100 900 C300 100 700 100 900 900' " +
            "stroke='#3A2E25' stroke-width='8'/><circle cx='500' cy='500' r='200' fill='#3A2E25'/></svg>\"}]}"
        server.enqueue(MockResponse().setBody(completionJson(blocks)))
        server.enqueue(MockResponse().setBody(completionJson(blocks)))
        scenario.onActivity { dispatchStroke(inkView(), listOf(100f to 200f, 400f to 350f, 700f to 250f)) }

        val answered = waitFor({ notebook.pages[0].answers.any { it.status == "complete" } }, 20_000)
        assertTrue(
            "answer never arrived; state=" +
                notebook.pages[0].answers.joinToString { "${it.status}:${it.error?.take(120)}" },
            answered,
        )

        val group = notebook.pages[0].answers.first { it.status == "complete" }
        assertEquals(listOf("text", "table", "drawing"), group.blocks.map { it.type })
        assertEquals(listOf("Lumos", "Nox"), group.blocks[1].rows!!.map { it[0] })

        // The reveal plays (~5 steps × 140 ms); the drawing must be visible
        // and animating, and the table must be on screen.
        val revealed = waitFor({
            val section = answerSection()
            val drawing = findInTree(section) { it is com.nisaba.render.DrawingInkView }
            val tableTitle = findInTree(section) { (it as? android.widget.TextView)?.text == "Incantations" }
            drawing != null && drawing.visibility == View.VISIBLE && tableTitle != null
        }, 10_000)
        assertTrue("drawing/table did not render", revealed)

        // And the whole answer persists.
        val persisted = waitFor({
            store.load(notebook.id).pages[0].answers.any { it.id == group.id }
        }, 10_000)
        assertTrue("mixed answer not persisted", persisted)
    }

    private fun findInTree(root: android.view.View, predicate: (android.view.View) -> Boolean): android.view.View? {
        if (predicate(root)) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findInTree(root.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }
}
