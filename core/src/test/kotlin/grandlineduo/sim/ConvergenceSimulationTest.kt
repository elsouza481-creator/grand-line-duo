package grandlineduo.sim

import grandlineduo.core.commands.GrantBerriesCommand
import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.HostReplica
import grandlineduo.test.assertEquals
import grandlineduo.test.test
import kotlin.random.Random

object ConvergenceSimulationTest {
    fun register() {
        test("dropped duplicated delayed and reordered events converge after reconnect") {
            val initial = WorldState(campaignId = "adverse-1")
            val host = HostReplica(initial)
            val client = ClientReplica(initial)
            val network = AdverseNetwork<grandlineduo.core.events.CampaignEvent>(
                seed = 77,
                profile = NetworkProfile(maxDelayTicks = 8, dropPercent = 25, duplicatePercent = 35),
            )

            repeat(40) { index ->
                val event = host.submit(
                    GrantBerriesCommand("cmd-$index", "p1", (index + 1).toLong()),
                    10_000L + index,
                ).event
                network.send(event)
                if (index % 3 == 0) network.advance(1, client::receive)
            }
            network.drain(client::receive)

            client.applySyncPlan(host.planReconnect(client.reconnectHello()))

            assertEquals(host.state, client.state)
            assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(client.state))
        }

        test("duplicate command delivery over bad network still executes once") {
            val initial = WorldState(campaignId = "adverse-command")
            val host = HostReplica(initial)
            val network = AdverseNetwork<GrantBerriesCommand>(
                seed = 91,
                profile = NetworkProfile(maxDelayTicks = 4, dropPercent = 0, duplicatePercent = 100),
            )
            val command = GrantBerriesCommand("same-command", "p2", 500)
            repeat(5) { network.send(command) }
            network.drain { host.submit(it, 50_000) }

            assertEquals(500L, host.state.partyBerries)
            assertEquals(1L, host.state.lastEventId)
            assertEquals(1, host.events.size)
        }

        test("one hundred seeded adverse sessions converge") {
            repeat(100) { seed ->
                val random = Random(seed)
                val initial = WorldState(campaignId = "stress-$seed")
                val host = HostReplica(initial)
                val client = ClientReplica(initial)
                val network = AdverseNetwork<grandlineduo.core.events.CampaignEvent>(
                    seed = seed.toLong(),
                    profile = NetworkProfile(
                        maxDelayTicks = 12,
                        dropPercent = 30,
                        duplicatePercent = 40,
                    ),
                )

                repeat(60) { index ->
                    val amount = random.nextLong(1, 1000)
                    val event = host.submit(
                        GrantBerriesCommand("s$seed-cmd-$index", if (index % 2 == 0) "p1" else "p2", amount),
                        seed * 100_000L + index,
                    ).event
                    network.send(event)
                    if (random.nextBoolean()) network.advance(random.nextInt(0, 3), client::receive)
                    if (index == 20) {
                        network.disconnect()
                        repeat(5) { skipped ->
                            val offlineEvent = host.submit(
                                GrantBerriesCommand("s$seed-offline-$skipped", "p1", 3),
                                seed * 100_000L + 10_000 + skipped,
                            ).event
                            network.send(offlineEvent)
                        }
                        network.reconnect()
                    }
                }
                network.drain(client::receive)
                client.applySyncPlan(host.planReconnect(client.reconnectHello()))

                assertEquals(
                    CanonicalStateHasher.hash(host.state),
                    CanonicalStateHasher.hash(client.state),
                    "seed=$seed",
                )
            }
        }
    }
}
