package grandlineduo.appshell

data class GameVisualProfile(
    val targetFps: Int,
    val maxParticles: Int,
    val maxParallaxLayers: Int,
    val textureScale: Float,
    val dynamicBlur: Boolean,
    val animatedWeather: Boolean,
) {
    companion object {
        /** Conservative defaults chosen for broad Android compatibility. */
        fun forDevice(memoryMb: Int, cpuCores: Int): GameVisualProfile = when {
            memoryMb <= 2300 || cpuCores <= 4 -> GameVisualProfile(
                targetFps = 30,
                maxParticles = 0,
                maxParallaxLayers = 1,
                textureScale = 0.50f,
                dynamicBlur = false,
                animatedWeather = false,
            )
            memoryMb <= 4600 || cpuCores <= 6 -> GameVisualProfile(
                targetFps = 30,
                maxParticles = 16,
                maxParallaxLayers = 2,
                textureScale = 0.70f,
                dynamicBlur = false,
                animatedWeather = false,
            )
            else -> GameVisualProfile(
                targetFps = 45,
                maxParticles = 24,
                maxParallaxLayers = 2,
                textureScale = 0.75f,
                dynamicBlur = false,
                animatedWeather = true,
            )
        }
    }
}
