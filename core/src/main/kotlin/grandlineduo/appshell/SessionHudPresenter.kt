package grandlineduo.appshell

object SessionHudPresenter {
    fun decorate(presentation: GamePresentation, hud: SessionHudState): GamePresentation =
        presentation.copy(status = listOf(hud.badge) + presentation.status)
}
