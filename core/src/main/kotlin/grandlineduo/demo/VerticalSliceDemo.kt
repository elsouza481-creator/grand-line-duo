package grandlineduo.demo

import grandlineduo.game.StormglassVerticalSlice
import java.nio.file.Files

fun main() {
    val saveDir = Files.createTempDirectory("grand-line-duo-demo")
    val result = StormglassVerticalSlice.run(seed = 20260818, saveDirectory = saveDir)
    println("=== ONE PIECE: GRAND LINE DUO — Stormglass Cay ===")
    result.transcript.forEach(::println)
    println()
    println("Status: ${result.scenario.stage}")
    println("Co-op combos: ${result.coopCombos}")
    println("Berries: ${result.world.partyBerries}")
    println("P1 bounty: ${result.world.players.getValue("p1").bounty}")
    println("P2 bounty: ${result.world.players.getValue("p2").bounty}")
    println("Final save: $saveDir")
}
