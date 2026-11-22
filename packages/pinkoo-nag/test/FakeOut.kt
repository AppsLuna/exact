package pinkoo.nag

/** The core's tests' [Out]: it records what the engine asked Android to show and play, in order. */
internal class FakeOut : Out {
    /** Every call, as `name` or `name:nag`, in order. */
    val calls = ArrayList<String>()
    val posts = ArrayList<Post>()
    val refreshes = ArrayList<Post>()
    val rings = ArrayList<Post>()
    val confirms = ArrayList<Pair<Int, String>>()
    var cleared = 0
    var stops = ArrayList<String>()
    /** What the bubble and the unlock wait were last told. */
    var bubble: Post? = null
    var unlock: Long? = null
    /** The reminder notification as the fake keeps it: the last post or refresh, gone with a clear. */
    var showing: Post? = null
    /** Whether the alarm rings now, as the fake keeps it. */
    var ringing: Post? = null

    override fun post(p: Post) { calls += "post:${p.nag}"; posts += p; showing = p }
    override fun refresh(p: Post) { calls += "refresh:${p.nag}"; refreshes += p; showing = p }
    override fun clear(nag: Int) { calls += "clear"; cleared++; showing = null }
    override fun ring(p: Post) { if (ringing?.nag != p.nag) calls += "ring:${p.nag}"; rings += p; ringing = p }
    override fun stopRing(why: String) { if (ringing != null) calls += "stopRing:$why"; stops += why; ringing = null }
    override fun bubble(p: Post?) { bubble = p }
    override fun waitUnlock(until: Long?) { unlock = until }
    override fun confirm(nag: Int, text: String) { calls += "confirm:$nag"; confirms += nag to text }

    fun reset() { calls.clear(); posts.clear(); refreshes.clear(); rings.clear(); confirms.clear(); stops.clear(); cleared = 0 }

    companion object {
        /** A fake installed as the engine's [Out], until [Engine.out] is set back. */
        fun install(): FakeOut = FakeOut().also { f -> Engine.out = { _, _ -> f } }
    }
}

/** The core's engine tests: [EngineCase] with a [FakeOut] as the engine's [Out], and every access granted unless a test
 * takes one away. */
internal abstract class CoreCase : EngineCase() {
    protected lateinit var out: FakeOut
    private var before: ((android.content.Context, Plan) -> Out)? = null

    @org.junit.Before fun setUpOut() {
        before = Engine.out
        out = FakeOut.install()
    }

    @org.junit.After fun tearDownOut() { before?.let { Engine.out = it } }
}
