package grandlineduo.game.powers

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object PowerDiscoveryEngineTest {
    fun register() {
        test("campaign receives one deterministic rare Devil Fruit discovery in a main chapter") {
            val a = PowerDiscoveryEngine.fruitDiscovery(12345L)
            val b = PowerDiscoveryEngine.fruitDiscovery(12345L)
            assertEquals(a, b)
            assertTrue(a.chapter in 2..4)
            assertTrue(PowerDiscoveryEngine.definition(a.definition.id) == a.definition)
        }

        test("latent Haoshoku is deterministic and rare across campaign seeds") {
            val first = PowerDiscoveryEngine.hasLatentHaoshoku(7788L, "p1")
            val second = PowerDiscoveryEngine.hasLatentHaoshoku(7788L, "p1")
            assertEquals(first, second)
            val latentCount = (0L until 256L).count { PowerDiscoveryEngine.hasLatentHaoshoku(it, "p1") }
            assertTrue(latentCount in 2..16, "Haoshoku potential should remain rare, got $latentCount/256")
        }
    }
}
