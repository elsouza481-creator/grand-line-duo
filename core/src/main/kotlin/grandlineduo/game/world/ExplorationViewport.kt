package grandlineduo.game.world

data class ExplorationViewportCell(
    val position: GridPosition,
    val tile: ExplorationTile,
    val interaction: ExplorationInteraction?,
    val isPlayer: Boolean,
    val playerIds: Set<String> = emptySet(),
)

data class ExplorationViewportState(
    val width: Int,
    val height: Int,
    val origin: GridPosition,
    val playerPosition: GridPosition,
    val cells: List<ExplorationViewportCell>,
    val playerPositions: Map<String, GridPosition> = emptyMap(),
)

object ExplorationViewport {
    fun build(
        map: ExplorationMap,
        playerPosition: GridPosition,
        playerPositions: Map<String, GridPosition> = emptyMap(),
        width: Int = 11,
        height: Int = 9,
    ): ExplorationViewportState {
        require(width > 0 && height > 0) { "Viewport dimensions must be positive" }
        require(width <= map.width && height <= map.height) { "Viewport cannot exceed map dimensions" }
        require(playerPosition.x in 0 until map.width && playerPosition.y in 0 until map.height) {
            "Player position must be inside the map"
        }
        playerPositions.forEach { (playerId, position) ->
            require(playerId.isNotBlank()) { "Player marker id cannot be blank" }
            require(position.x in 0 until map.width && position.y in 0 until map.height) {
                "Player marker $playerId must be inside the map"
            }
        }

        val maxOriginX = map.width - width
        val maxOriginY = map.height - height
        val originX = (playerPosition.x - width / 2).coerceIn(0, maxOriginX)
        val originY = (playerPosition.y - height / 2).coerceIn(0, maxOriginY)
        val origin = GridPosition(originX, originY)
        val idsByPosition = playerPositions.entries
            .groupBy(keySelector = { it.value }, valueTransform = { it.key })
            .mapValues { (_, ids) -> ids.toSortedSet() }

        val cells = buildList(width * height) {
            for (y in originY until originY + height) {
                for (x in originX until originX + width) {
                    val position = GridPosition(x, y)
                    add(
                        ExplorationViewportCell(
                            position = position,
                            tile = map.tileAt(position),
                            interaction = map.interactions[position],
                            isPlayer = position == playerPosition,
                            playerIds = idsByPosition[position].orEmpty(),
                        )
                    )
                }
            }
        }

        return ExplorationViewportState(
            width = width,
            height = height,
            origin = origin,
            playerPosition = playerPosition,
            cells = cells,
            playerPositions = playerPositions.toSortedMap(),
        )
    }
}
