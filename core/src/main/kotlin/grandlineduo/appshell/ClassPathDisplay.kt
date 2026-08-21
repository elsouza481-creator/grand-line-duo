package grandlineduo.appshell

import grandlineduo.game.character.ClassMasteryEngine
import grandlineduo.game.character.ClassMasteryState
import grandlineduo.game.character.ClassPath

object ClassPathDisplay {
    fun label(path: ClassPath): String = when (path) {
        ClassPath.SWORDSMAN -> "Espadachim"
        ClassPath.BRAWLER -> "Lutador"
        ClassPath.GUNNER -> "Atirador"
        ClassPath.NAVIGATOR -> "Navegador"
        ClassPath.DOCTOR -> "Médico"
        ClassPath.SHIPWRIGHT -> "Carpinteiro"
        ClassPath.COOK -> "Cozinheiro"
        ClassPath.ROGUE -> "Ladino"
        ClassPath.SCHOLAR -> "Erudito"
        ClassPath.CAPTAIN -> "Capitão"
    }

    fun progress(state: ClassMasteryState, path: ClassPath): String {
        val level = state.levelOf(path)
        val experience = state.experienceOf(path)
        val required = ClassMasteryEngine.experienceRequiredForLevel(level)
        return "${label(path)} • nível $level • $experience/$required XP"
    }

    fun primaryProgress(state: ClassMasteryState): String =
        "Classe ${progress(state, state.primaryClass)}"
}
