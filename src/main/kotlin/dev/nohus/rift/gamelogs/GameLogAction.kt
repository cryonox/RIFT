package dev.nohus.rift.gamelogs

sealed interface GameLogAction {
    data class UnderAttack(val target: String) : GameLogAction
    data class Attacking(val target: String) : GameLogAction
    data class BeingWarpScrambled(val target: String) : GameLogAction
    data class Decloaked(val by: String) : GameLogAction
}