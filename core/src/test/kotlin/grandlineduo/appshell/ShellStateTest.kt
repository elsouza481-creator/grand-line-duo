package grandlineduo.appshell

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ShellStateTest {
    fun register() {
        test("shell starts at recoverable home state") {
            val state = ShellState.initial()
            assertEquals(ShellMode.HOME, state.mode)
            assertTrue(state.canCreate)
            assertTrue(state.canFind)
        }

        test("host started only reports hosting after server bind") {
            val state = ShellStateReducer.reduce(
                ShellState.initial(),
                ShellAction.HostStarted(campaignId = "campaign-1", port = 43123),
            )
            assertEquals(ShellMode.HOSTING, state.mode)
            assertEquals("campaign-1", state.campaignId)
            assertEquals(43123, state.port)
            assertTrue(!state.connected)
        }

        test("search state does not pretend client is connected") {
            val state = ShellStateReducer.reduce(ShellState.initial(), ShellAction.SearchStarted)
            assertEquals(ShellMode.SEARCHING, state.mode)
            assertTrue(!state.connected)
            assertTrue(!state.canCreate)
        }

        test("client connected state is only created by successful handshake action") {
            val searching = ShellStateReducer.reduce(ShellState.initial(), ShellAction.SearchStarted)
            val connected = ShellStateReducer.reduce(
                searching,
                ShellAction.ClientConnected("campaign-2", "192.168.1.8", 43123),
            )
            assertEquals(ShellMode.CONNECTED, connected.mode)
            assertEquals("campaign-2", connected.campaignId)
            assertEquals("192.168.1.8", connected.remoteHost)
            assertTrue(connected.connected)
        }

        test("network failure leaves a recoverable retry state") {
            val searching = ShellStateReducer.reduce(ShellState.initial(), ShellAction.SearchStarted)
            val failed = ShellStateReducer.reduce(searching, ShellAction.Failed("Nenhuma aventura encontrada"))
            assertEquals(ShellMode.ERROR, failed.mode)
            assertTrue(failed.canCreate)
            assertTrue(failed.canFind)
            assertEquals("Nenhuma aventura encontrada", failed.message)
        }
    }
}
