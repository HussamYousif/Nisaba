package com.nisaba.llm

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
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
            // Explicit: settings persist to app prefs, and other tests (and
            // earlier runs on the same install) may have turned auto-summon
            // off — the idle flow must not silently depend on leftover state.
            autoSummonEnabled = true
        }
        notebook = store.createBlank()
        notebook.title = "Summon Test"

        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { activity ->
            screen = NotebookScreen(activity).apply {
                bind(notebook, settings, onBack = {}, onSave = { nb, _ -> store.save(nb) })
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

        // The answer is rendered as ink on the canvas.
        val rendered = waitFor({ inkView().answerItemCount > 0 }, 5_000)
        assertTrue("answer ink did not render on the canvas", rendered)

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

        // The user keeps writing → cancel-on-resume (F3.2): the request dies
        // and the ink draws back into the stroke.
        scenario.onActivity { dispatchStroke(inkView(), listOf(800f to 400f, 900f to 500f)) }
        scenario.onActivity { settings.autoSummonEnabled = false } // no idle re-summon

        val receded = waitFor({ !inkView().isSummonInkActive }, 10_000)
        assertTrue("summon ink never receded", receded)

        // User cancellation is not an event worth persisting (audit P0-5): no
        // group of any status may be written into the notebook.
        Thread.sleep(4_500) // past the mock's 4 s delay — the late reply must not land
        assertTrue(
            "cancelled summon wrote a junk group: " +
                notebook.pages[0].answers.joinToString { "${it.status}:${it.error?.take(80)}" },
            notebook.pages[0].answers.isEmpty(),
        )
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

        // The reveal plays; the drawing ink (paths) and the ruled table
        // (rules) must be on the canvas.
        val revealed = waitFor({
            val ink = inkView()
            ink.answerPathCount > 0 && ink.answerRuleCount > 0
        }, 10_000)
        assertTrue("drawing/table ink did not render", revealed)

        // And the whole answer persists.
        val persisted = waitFor({
            store.load(notebook.id).pages[0].answers.any { it.id == group.id }
        }, 10_000)
        assertTrue("mixed answer not persisted", persisted)
    }

}
