package grandlineduo.appshell

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object GameVisualProfileTest {
    fun register() {
        test("popular phone profile caps effects and targets thirty fps") {
            val profile = GameVisualProfile.forDevice(memoryMb = 4096, cpuCores = 8)
            assertEquals(30, profile.targetFps)
            assertTrue(profile.maxParticles <= 24)
            assertTrue(profile.maxParallaxLayers <= 2)
            assertTrue(profile.textureScale <= 0.75f)
            assertEquals(false, profile.dynamicBlur)
        }

        test("low memory phone falls back to ultra light profile") {
            val profile = GameVisualProfile.forDevice(memoryMb = 2048, cpuCores = 4)
            assertEquals(30, profile.targetFps)
            assertEquals(0, profile.maxParticles)
            assertEquals(1, profile.maxParallaxLayers)
            assertTrue(profile.textureScale <= 0.5f)
        }
    }
}
